/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.samples.petclinic.owner;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import jakarta.validation.Valid;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 【中文补充说明】宠物 MVC 控制器：负责在指定主人名下新增/编辑宠物。 类级 {@code @RequestMapping("/owners/{ownerId}")}
 * 使全部子路径共享 ownerId 前缀。
 *
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 * <li>三个 {@code @ModelAttribute} 前置方法依次准备 types（类型下拉框）、owner（路径主人）、 pet（新建空对象或按 petId
 * 定位的既有宠物）；</li>
 * <li>{@code @InitBinder("pet")} 挂载 {@link PetValidator}（编程式校验）并禁绑
 * {@code id}/{@code *.id}；{@code @InitBinder("owner")} 仅禁绑 id；</li>
 * <li>三层校验防线：PetValidator（必填/长度）→ 控制器业务校验（同名宠物、生日不得为 未来日期）→ 数据库
 * {@code unique_owner_pet_name} 唯一约束（通过 {@code DataIntegrityViolationException}
 * 消息识别兜底，捕获后回表单页提示重名）；</li>
 * <li>持久化以 Owner 为聚合根：新增经 {@code owner.addPet} + {@code saveAndFlush} （立即刷库以触发唯一约束）；编辑经
 * {@link #updatePetDetails} 在受管实体上逐字段覆盖。</li>
 * </ul>
 *
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Wick Dynex
 */
@Controller
@RequestMapping("/owners/{ownerId}")
class PetController {

	/**
	 * SLF4J 日志记录器：记录宠物新建/编辑等写操作及唯一约束冲突（【中文补充说明】新增埋点）。
	 */
	private static final Logger logger = LoggerFactory.getLogger(PetController.class);

	/**
	 * 宠物新建/编辑共用的表单视图名（对应 templates/pets/createOrUpdatePetForm.html）。
	 */
	private static final String VIEWS_PETS_CREATE_OR_UPDATE_FORM = "pets/createOrUpdatePetForm";

	/**
	 * 主人仓库（Owner 聚合的持久化出口，宠物经其级联保存）。
	 */
	private final OwnerRepository owners;

	/**
	 * 宠物类型仓库（提供表单下拉框数据）。
	 */
	private final PetTypeRepository types;

	/**
	 * 构造器注入仓库依赖。
	 * @param owners 主人仓库（{@link OwnerRepository} 类型）
	 * @param types 宠物类型仓库（{@link PetTypeRepository} 类型）
	 */
	public PetController(OwnerRepository owners, PetTypeRepository types) {
		this.owners = owners;
		this.types = types;
	}

	/**
	 * 【@ModelAttribute 前置方法】填充模型属性 "types"：全部宠物类型（按名称排序）， 供表单下拉框渲染。在本控制器每个 handler 之前执行。
	 * @return 全部宠物类型集合（{@link Collection}<{@link PetType}>），永不为空集合以外的情况
	 */
	@ModelAttribute("types")
	public Collection<PetType> populatePetTypes() {
		return this.types.findPetTypes();
	}

	/**
	 * 【@ModelAttribute 前置方法】按路径变量加载主人并放入模型（属性名 "owner"）。
	 * @param ownerId URL 路径变量主人主键（{@code int} 类型）
	 * @return 加载到的 {@link Owner} 实例
	 * @throws IllegalArgumentException 当指定 ownerId 的主人不存在时抛出（未做 404 映射）
	 */
	@ModelAttribute("owner")
	public Owner findOwner(@PathVariable("ownerId") int ownerId) {
		Optional<Owner> optionalOwner = this.owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));
		return owner;
	}

	/**
	 * 【@ModelAttribute 前置方法】准备模型属性 "pet"（表单命令对象）。
	 *
	 * <p>
	 * 行为：路径无 petId（新增场景）→ 返回全新空 {@link Pet}； 有 petId（编辑场景）→ 在主人名下按 id
	 * 查找既有宠物（{@code owner.getPet(petId)}， 找不到时为 {@code null}，后续校验/更新按缺态处理）。
	 * </p>
	 * @param ownerId URL 路径变量主人主键（{@code int} 类型，用于加载主人）
	 * @param petId URL 路径变量宠物主键（{@link Integer} 类型）；新增场景为 {@code null}
	 * @return 新建的空宠物或主人名下的既有宠物（{@link Pet} 类型；编辑场景 id 无效时可能为 {@code null}）
	 */
	@ModelAttribute("pet")
	public Pet findPet(@PathVariable("ownerId") int ownerId,
			@PathVariable(name = "petId", required = false) Integer petId) {

		if (petId == null) {
			return new Pet();
		}

		Optional<Owner> optionalOwner = this.owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));
		return owner.getPet(petId);
	}

	/**
	 * 【数据绑定安全】owner 对象的绑定器配置：禁绑 {@code id}/{@code *.id}， 防止表单篡改主人主键（仅对名为 "owner"
	 * 的模型属性生效）。
	 * @param dataBinder owner 属性的数据绑定器（{@link WebDataBinder} 类型）
	 */
	@InitBinder("owner")
	public void initOwnerBinder(WebDataBinder dataBinder) {
		dataBinder.setDisallowedFields("id", "*.id");
	}

	/**
	 * 【数据绑定与校验】pet 对象的绑定器配置：替换默认校验器为 {@link PetValidator} （编程式规则：name 必填且 ≤30 字符、新增时 type
	 * 必填、birthDate 必填）， 同时禁绑 {@code id}/{@code *.id} 防止篡改宠物主键。
	 * @param dataBinder pet 属性的数据绑定器（{@link WebDataBinder} 类型）
	 */
	@InitBinder("pet")
	public void initPetBinder(WebDataBinder dataBinder) {
		dataBinder.setValidator(new PetValidator());
		dataBinder.setDisallowedFields("id", "*.id");
	}

	/**
	 * GET /owners/{ownerId}/pets/new —— 打开"新增宠物"表单页。
	 *
	 * <p>
	 * 创建一个空 Pet 并经 {@code owner.addPet} 挂到主人聚合上（尚未持久化）， 使表单模板能通过 owner.pets
	 * 渲染上下文；随后返回视图名。
	 * </p>
	 * @param owner 路径主人（由 {@link #findOwner} 提供的模型属性）
	 * @param model 视图模型（{@link ModelMap} 类型，本方法未直接使用）
	 * @return 视图名 {@code "pets/createOrUpdatePetForm"}
	 */
	@GetMapping("/pets/new")
	public String initCreationForm(Owner owner, ModelMap model) {
		Pet pet = new Pet();
		owner.addPet(pet);
		return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
	}

	/**
	 * POST /owners/{ownerId}/pets/new —— 处理"新增宠物"表单提交。
	 *
	 * <p>
	 * 流程（三层校验 + 约束兜底）： ① {@link PetValidator}（经 {@code @Valid} 触发）校验 name/type/birthDate
	 * 基础规则； ② 业务校验：同名宠物查重（{@code owner.getPet(name, ignoreNew=true)} 忽略临时宠物）
	 * 与生日不得晚于今天（{@code typeMismatch.birthDate}）； ③ {@code owner.addPet} +
	 * {@code saveAndFlush} 立即刷库触发 {@code unique_owner_pet_name} 唯一约束——并发重名时捕获
	 * {@code DataIntegrityViolationException}，经 {@link #isDuplicatePetNameViolation}
	 * 判定为重名冲突后 reject "duplicate" 回表单页；其他数据完整性异常原样抛出。 成功后 flash 消息并重定向回主人详情页。
	 * </p>
	 * @param owner 路径主人（{@link Owner} 类型，模型属性注入）
	 * @param pet 表单绑定的宠物对象（{@link Pet} 类型）
	 * @param result 校验结果容器（{@link BindingResult} 类型）
	 * @param redirectAttributes 重定向属性（携带一次性 flash 消息）
	 * @return 校验失败或约束冲突返回表单视图名；成功返回 {@code "redirect:/owners/{ownerId}"}
	 */
	@PostMapping("/pets/new")
	public String processCreationForm(Owner owner, @Valid Pet pet, BindingResult result,
			RedirectAttributes redirectAttributes) {

		if (StringUtils.hasText(pet.getName()) && pet.isNew() && owner.getPet(pet.getName(), true) != null) {
			logger.warn("Duplicate pet name rejected on creation: ownerId={}, name={}", owner.getId(), pet.getName());
			result.rejectValue("name", "duplicate", "already exists");
		}

		LocalDate currentDate = LocalDate.now();
		if (pet.getBirthDate() != null && pet.getBirthDate().isAfter(currentDate)) {
			logger.warn("Future birth date rejected on creation: ownerId={}, name={}, birthDate={}", owner.getId(),
					pet.getName(), pet.getBirthDate());
			result.rejectValue("birthDate", "typeMismatch.birthDate");
		}

		if (result.hasErrors()) {
			logger.warn("Pet creation validation failed: ownerId={}, name={}", owner.getId(), pet.getName());
			return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
		}

		try {
			owner.addPet(pet);
			this.owners.saveAndFlush(owner);
		}
		catch (DataIntegrityViolationException ex) {
			if (!isDuplicatePetNameViolation(ex)) {
				logger.error("Unexpected data integrity violation while creating pet: ownerId={}, name={}",
						owner.getId(), pet.getName(), ex);
				throw ex;
			}
			logger.warn("Duplicate pet name constraint hit on creation: ownerId={}, name={}", owner.getId(),
					pet.getName());
			result.rejectValue("name", "duplicate", "already exists");
			return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
		}
		logger.info("Pet created: ownerId={}, petId={}, name={}, type={}, birthDate={}", owner.getId(), pet.getId(),
				pet.getName(), pet.getType(), pet.getBirthDate());
		redirectAttributes.addFlashAttribute("message", "New Pet has been Added");
		return "redirect:/owners/{ownerId}";
	}

	/**
	 * GET /owners/{ownerId}/pets/{petId}/edit —— 打开"编辑宠物"表单页。
	 *
	 * <p>
	 * 目标宠物已由 {@link #findPet} 按 petId 放入模型（含类型/生日回填），本方法仅返回视图名。
	 * </p>
	 * @return 视图名 {@code "pets/createOrUpdatePetForm"}
	 */
	@GetMapping("/pets/{petId}/edit")
	public String initUpdateForm() {
		return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
	}

	/**
	 * POST /owners/{ownerId}/pets/{petId}/edit —— 处理"编辑宠物"表单提交。
	 *
	 * <p>
	 * 流程：① {@link PetValidator} 基础校验（编辑场景 type 不强制）； ②
	 * 业务校验：同名查重（{@code owner.getPet(name, ignoreNew=false)}，命中且 id 不同于 当前宠物时 reject
	 * "duplicate"）+ 生日不得晚于今天； ③ 委托 {@link #updatePetDetails} 在受管实体上覆盖字段并
	 * {@code saveAndFlush}——并发重名触发唯一约束时同样按重名处理回表单页。 成功后 flash 消息并重定向回主人详情页。
	 * </p>
	 * @param owner 路径主人（{@link Owner} 类型，模型属性注入）
	 * @param pet 表单绑定的宠物对象（{@link Pet} 类型）
	 * @param result 校验结果容器（{@link BindingResult} 类型）
	 * @param redirectAttributes 重定向属性（携带一次性 flash 消息）
	 * @return 校验失败或约束冲突返回表单视图名；成功返回 {@code "redirect:/owners/{ownerId}"}
	 */
	@PostMapping("/pets/{petId}/edit")
	public String processUpdateForm(Owner owner, @Valid Pet pet, BindingResult result,
			RedirectAttributes redirectAttributes) {

		String petName = pet.getName();

		// checking if the pet name already exists for the owner
		if (StringUtils.hasText(petName)) {
			Pet existingPet = owner.getPet(petName, false);
			if (existingPet != null && !Objects.equals(existingPet.getId(), pet.getId())) {
				logger.warn("Duplicate pet name rejected on update: ownerId={}, petId={}, name={}", owner.getId(),
						pet.getId(), petName);
				result.rejectValue("name", "duplicate", "already exists");
			}
		}

		LocalDate currentDate = LocalDate.now();
		if (pet.getBirthDate() != null && pet.getBirthDate().isAfter(currentDate)) {
			logger.warn("Future birth date rejected on update: ownerId={}, petId={}, birthDate={}", owner.getId(),
					pet.getId(), pet.getBirthDate());
			result.rejectValue("birthDate", "typeMismatch.birthDate");
		}

		if (result.hasErrors()) {
			logger.warn("Pet update validation failed: ownerId={}, petId={}, name={}", owner.getId(), pet.getId(),
					pet.getName());
			return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
		}

		try {
			updatePetDetails(owner, pet);
		}
		catch (DataIntegrityViolationException ex) {
			if (!isDuplicatePetNameViolation(ex)) {
				logger.error("Unexpected data integrity violation while updating pet: ownerId={}, petId={}, name={}",
						owner.getId(), pet.getId(), pet.getName(), ex);
				throw ex;
			}
			logger.warn("Duplicate pet name constraint hit on update: ownerId={}, petId={}, name={}", owner.getId(),
					pet.getId(), pet.getName());
			result.rejectValue("name", "duplicate", "already exists");
			return VIEWS_PETS_CREATE_OR_UPDATE_FORM;
		}
		logger.info("Pet updated: ownerId={}, petId={}, name={}, type={}, birthDate={}", owner.getId(), pet.getId(),
				pet.getName(), pet.getType(), pet.getBirthDate());
		redirectAttributes.addFlashAttribute("message", "Pet details has been edited");
		return "redirect:/owners/{ownerId}";
	}

	/**
	 * Updates the pet details if it exists or adds a new pet to the owner.
	 *
	 * <p>
	 * 【中文补充说明】编辑落库核心逻辑（私有辅助方法）： ① 断言 {@code pet.getId()} 非空（编辑场景必须有主键，否则抛
	 * {@code IllegalStateException}）； ② 在主人名下按 id 定位<b>受管</b>的既有宠物，命中则逐字段覆盖
	 * name/birthDate/type（同一持久化上下文内更新，flush 时生成 UPDATE）； ③
	 * 未命中（异常情况）则退化为新增（{@code owner.addPet}）； ④ {@code saveAndFlush(owner)} 立即刷库，触发
	 * {@code unique_owner_pet_name} 唯一约束以捕获并发重名。
	 * </p>
	 * @param owner The owner of the pet —— 宠物所属主人（{@link Owner} 类型，受管实体）
	 * @param pet The pet with updated details —— 承载表单新值的宠物对象 （{@link Pet} 类型，须携带非空 id）
	 * @throws IllegalStateException 当 {@code pet.getId()} 为 {@code null} 时抛出
	 * @throws DataIntegrityViolationException 当并发场景触发数据库唯一约束等完整性冲突时 向上抛出（由调用方判定是否为重名冲突）
	 */
	private void updatePetDetails(Owner owner, Pet pet) {
		Integer id = pet.getId();
		Assert.state(id != null, "'pet.getId()' must not be null");
		Pet existingPet = owner.getPet(id);
		if (existingPet != null) {
			// Update existing pet's properties
			existingPet.setName(pet.getName());
			existingPet.setBirthDate(pet.getBirthDate());
			existingPet.setType(pet.getType());
		}
		else {
			logger.debug("Pet id={} not found under owner id={}, falling back to addPet", id, owner.getId());
			owner.addPet(pet);
		}
		this.owners.saveAndFlush(owner);
	}

	/**
	 * 判断数据完整性异常是否由"同一主人下宠物重名"唯一约束引起（私有辅助方法）。
	 *
	 * <p>
	 * 实现方式：检查异常消息（小写化后）是否包含约束名 {@code unique_owner_pet_name} （对应 schema.sql 中
	 * {@code UNIQUE(owner_id, name)} 约束）。 注意：该方式依赖异常消息文本，跨数据库/驱动/ORM 版本存在措辞漂移风险。
	 * </p>
	 * @param ex 待判定的数据完整性异常（{@link DataIntegrityViolationException} 类型）
	 * @return 消息中包含 {@code unique_owner_pet_name} 时返回 {@code true}（视为重名冲突）； 否则
	 * {@code false}（其他完整性冲突，调用方将原样抛出）
	 */
	private boolean isDuplicatePetNameViolation(DataIntegrityViolationException ex) {
		String message = ex.getMessage();
		return message != null && message.toLowerCase().contains("unique_owner_pet_name");
	}

}
