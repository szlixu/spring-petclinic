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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import jakarta.validation.Valid;

import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 【中文补充说明】宠物主人 MVC 控制器：负责主人的新建、编辑、查找（分页）与详情展示， 共 8 个请求处理方法，视图均为 Thymeleaf 模板（owners/ 目录）。
 *
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 * <li>类级 {@code @ModelAttribute("owner") findOwner} 在<b>每个</b> handler 执行前运行： URL 带
 * ownerId 时从仓库加载，否则 new 一个空 Owner——同时承担"表单回填对象"与 "命令对象"两种角色；</li>
 * <li>{@code @InitBinder setAllowedFields} 禁止表单绑定 {@code id}/{@code *.id} 字段，
 * 防止客户端篡改主键；更新流程另做 URL 与表单 id 一致性复核（见 {@link #processUpdateOwnerForm}）；</li>
 * <li>列表分页：pageSize 固定 5，页码从 1 开始（前端语义），转换为 0 基后传入 Spring Data；</li>
 * <li>操作结果通过 {@code RedirectAttributes#addFlashAttribute} 传递一次性消息 （message/error），配合
 * PRG（Post-Redirect-Get）模式防重复提交。</li>
 * </ul>
 *
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Michael Isvy
 * @author Wick Dynex
 */
@Controller
class OwnerController {

	/**
	 * SLF4J 日志记录器：记录主人新建/更新等写操作及校验失败（【中文补充说明】新增埋点）。
	 */
	private static final Logger logger = LoggerFactory.getLogger(OwnerController.class);

	/**
	 * 主人新建/编辑共用的表单视图名（对应 templates/owners/createOrUpdateOwnerForm.html）。
	 */
	private static final String VIEWS_OWNER_CREATE_OR_UPDATE_FORM = "owners/createOrUpdateOwnerForm";

	/**
	 * 主人仓库（Owner 聚合的唯一持久化出口）。
	 */
	private final OwnerRepository owners;

	/**
	 * 构造器注入主人仓库。
	 * @param owners 主人仓库（{@link OwnerRepository} 类型），由 Spring Data JPA 代理提供实现
	 */
	public OwnerController(OwnerRepository owners) {
		this.owners = owners;
	}

	/**
	 * 【数据绑定安全】注册禁绑字段：禁止表单请求参数绑定到 {@code id} 与任何嵌套的 {@code *.id} 属性。
	 *
	 * <p>
	 * 目的：主键只能来自 URL 路径变量或数据库，防止客户端通过伪造表单字段篡改主键 （例如把 A 主人的更新提交到 B 主人名下）。
	 * </p>
	 * @param dataBinder 当前请求的数据绑定器（{@link WebDataBinder} 类型）
	 */
	@InitBinder
	public void setAllowedFields(WebDataBinder dataBinder) {
		dataBinder.setDisallowedFields("id", "*.id");
	}

	/**
	 * 【@ModelAttribute 前置方法】在本控制器每个 handler 执行前准备 {@code owner} 模型属性。
	 *
	 * <p>
	 * 行为：URL 中含 {@code ownerId}（编辑/详情等路径）时从仓库加载对应主人，不存在则抛
	 * {@code IllegalArgumentException}（未做 404 映射，最终表现为 500 错误页）； 不含 ownerId（新建表单）时返回全新空
	 * {@link Owner}。
	 * </p>
	 * @param ownerId URL 路径变量（{@link Integer} 类型）；新建场景为 {@code null}
	 * @return 加载到的或新建的 {@link Owner} 实例，作为模型属性 "owner" 供后续 handler 与视图使用
	 * @throws IllegalArgumentException 当指定 ownerId 的主人在数据库中不存在时抛出
	 */
	@ModelAttribute("owner")
	public Owner findOwner(@PathVariable(name = "ownerId", required = false) Integer ownerId) {
		return ownerId == null ? new Owner()
				: this.owners.findById(ownerId)
					.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId
							+ ". Please ensure the ID is correct " + "and the owner exists in the database."));
	}

	/**
	 * GET /owners/new —— 打开"新建主人"表单页。
	 *
	 * <p>
	 * 表单对象已由 {@link #findOwner} 以空 Owner 形态放入模型，本方法仅返回视图名。
	 * </p>
	 * @return 视图名 {@code "owners/createOrUpdateOwnerForm"}（新建/编辑共用模板）
	 */
	@GetMapping("/owners/new")
	public String initCreationForm() {
		return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
	}

	/**
	 * POST /owners/new —— 处理"新建主人"表单提交。
	 *
	 * <p>
	 * 流程：Bean Validation（{@code @Valid}，校验 firstName/lastName/address/city/telephone 的
	 * NotBlank/Size/Pattern 约束）失败 → flash error 消息并返回表单页（保留输入）； 通过 →
	 * {@code owners.save(owner)} 持久化（JPA 生成主键）→ flash 成功消息 → 重定向到新建主人的详情页（PRG 模式）。
	 * </p>
	 * @param owner 表单绑定的主人对象（{@link Owner} 类型，来自 {@link #findOwner}）
	 * @param result 校验结果容器（{@link BindingResult} 类型），紧随 {@code @Valid} 参数
	 * @param redirectAttributes 重定向属性（{@link RedirectAttributes} 类型），携带一次性 flash 消息
	 * @return 校验失败返回表单视图名；成功返回 {@code "redirect:/owners/{新id}"}
	 */
	@PostMapping("/owners/new")
	public String processCreationForm(@Valid Owner owner, BindingResult result, RedirectAttributes redirectAttributes) {
		if (result.hasErrors()) {
			logger.warn("Owner creation validation failed: lastName={}", owner.getLastName());
			redirectAttributes.addFlashAttribute("error", "There was an error in creating the owner.");
			return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
		}

		this.owners.save(owner);
		logger.info("Owner created: id={}, lastName={}", owner.getId(), owner.getLastName());
		redirectAttributes.addFlashAttribute("message", "New Owner Created");
		return "redirect:/owners/" + owner.getId();
	}

	/**
	 * GET /owners/find —— 打开"查找主人"表单页（按姓氏检索）。
	 * @return 视图名 {@code "owners/findOwners"}
	 */
	@GetMapping("/owners/find")
	public String initFindForm() {
		return "owners/findOwners";
	}

	/**
	 * GET /owners —— 处理"查找主人"表单提交（GET 查询），支持分页。
	 *
	 * <p>
	 * 流程：① 取表单姓氏并 {@code strip()}（null 视为空串=全量搜索）； ②
	 * {@link #findPaginatedForOwnersLastName} 分页查询；③ 结果分支： 空结果 → 对 lastName 字段 reject
	 * "notFound" 并返回查找页； 恰好 1 条 → 重定向直达该主人详情页；多条 → 填充分页模型并返回列表页。
	 * </p>
	 * @param page 页码（{@code int}，从 1 开始，默认值 1）
	 * @param owner 表单绑定的主人对象（承载 lastName 查询条件）
	 * @param result 绑定/校验结果容器（用于注入 "notFound" 字段错误）
	 * @param model 视图模型（{@link Model} 类型），多条结果时写入分页属性
	 * @return 空结果返回 {@code "owners/findOwners"}；单条返回 {@code "redirect:/owners/{id}"}；多条返回
	 * {@code "owners/ownersList"}
	 */
	@GetMapping("/owners")
	public String processFindForm(@RequestParam(defaultValue = "1") int page, Owner owner, BindingResult result,
			Model model) {
		// allow parameterless GET request for /owners to return all records
		String lastName = owner.getLastName();
		if (lastName == null) {
			lastName = ""; // empty string signifies broadest possible search
		}
		else {
			lastName = lastName.strip();
		}

		// find owners by last name
		Page<Owner> ownersResults = findPaginatedForOwnersLastName(page, lastName);
		if (ownersResults.isEmpty()) {
			// no owners found
			result.rejectValue("lastName", "notFound", "not found");
			return "owners/findOwners";
		}

		if (ownersResults.getTotalElements() == 1) {
			// 1 owner found
			owner = ownersResults.iterator().next();
			return "redirect:/owners/" + owner.getId();
		}

		// multiple owners found
		return addPaginationModel(page, model, ownersResults);
	}

	/**
	 * 将分页元数据与当前页数据写入视图模型（私有辅助方法）。
	 *
	 * <p>
	 * 写入属性：currentPage（当前页码，1 基）、totalPages（总页数）、totalItems（总条数）、 listOwners（当前页主人列表），供
	 * ownersList.html 模板渲染列表与分页导航条。
	 * </p>
	 * @param page 当前页码（{@code int}，1 基）
	 * @param model 视图模型（{@link Model} 类型）
	 * @param paginated 分页查询结果（{@link Page}<{@link Owner}>）
	 * @return 视图名 {@code "owners/ownersList"}
	 */
	private String addPaginationModel(int page, Model model, Page<Owner> paginated) {
		List<Owner> listOwners = paginated.getContent();
		model.addAttribute("currentPage", page);
		model.addAttribute("totalPages", paginated.getTotalPages());
		model.addAttribute("totalItems", paginated.getTotalElements());
		model.addAttribute("listOwners", listOwners);
		return "owners/ownersList";
	}

	/**
	 * 按姓氏前缀执行分页查询（私有辅助方法）。
	 *
	 * <p>
	 * 实现细节：页大小硬编码为 5；入参页码为 1 基，转换为 Spring Data 的 0 基页号
	 * （{@code PageRequest.of(page - 1, 5)}）。注意：page ≤ 0 时会因负页号抛出
	 * {@code IllegalArgumentException}（表现为 500）。
	 * </p>
	 * @param page 页码（{@code int}，1 基，应 ≥ 1）
	 * @param lastname 姓氏前缀（{@link String} 类型），空串表示全量
	 * @return 分页结果（{@link Page}<{@link Owner}>）
	 */
	private Page<Owner> findPaginatedForOwnersLastName(int page, String lastname) {
		int pageSize = 5;
		Pageable pageable = PageRequest.of(page - 1, pageSize);
		return owners.findByLastNameStartingWith(lastname, pageable);
	}

	/**
	 * GET /owners/{ownerId}/edit —— 打开"编辑主人"表单页。
	 *
	 * <p>
	 * 当前主人数据已由 {@link #findOwner} 依据路径变量加载进模型，本方法仅返回视图名 （与新建共用模板，Thymeleaf 依据模型数据回填表单）。
	 * </p>
	 * @return 视图名 {@code "owners/createOrUpdateOwnerForm"}
	 */
	@GetMapping("/owners/{ownerId}/edit")
	public String initUpdateOwnerForm() {
		return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
	}

	/**
	 * POST /owners/{ownerId}/edit —— 处理"编辑主人"表单提交。
	 *
	 * <p>
	 * 流程：① Bean Validation 失败 → flash error + 返回表单页； ② id 一致性复核：表单对象的 id（由
	 * {@link #findOwner} 从 URL 加载）与路径 ownerId 不一致 → reject "mismatch" + flash error +
	 * 重定向回编辑页（防路径与对象错位）； ③ 一致时以路径 ownerId 为准回写 id 并 {@code save(owner)}（托管状态更新）→ flash
	 * 成功消息 → 重定向回详情页。
	 * </p>
	 * @param owner 表单绑定的主人对象（{@link Owner} 类型）
	 * @param result 校验结果容器（{@link BindingResult} 类型）
	 * @param ownerId URL 路径变量主人主键（{@code int} 类型）
	 * @param redirectAttributes 重定向属性（携带一次性 flash 消息）
	 * @return 校验失败返回表单视图名；id 不匹配返回 {@code "redirect:/owners/{ownerId}/edit"}； 成功返回
	 * {@code "redirect:/owners/{ownerId}"}
	 */
	@PostMapping("/owners/{ownerId}/edit")
	public String processUpdateOwnerForm(@Valid Owner owner, BindingResult result, @PathVariable("ownerId") int ownerId,
			RedirectAttributes redirectAttributes) {
		if (result.hasErrors()) {
			logger.warn("Owner update validation failed: ownerId={}", ownerId);
			redirectAttributes.addFlashAttribute("error", "There was an error in updating the owner.");
			return VIEWS_OWNER_CREATE_OR_UPDATE_FORM;
		}

		if (!Objects.equals(owner.getId(), ownerId)) {
			logger.warn("Owner ID mismatch rejected: path ownerId={}, form id={}", ownerId, owner.getId());
			result.rejectValue("id", "mismatch", "The owner ID in the form does not match the URL.");
			redirectAttributes.addFlashAttribute("error", "Owner ID mismatch. Please try again.");
			return "redirect:/owners/{ownerId}/edit";
		}

		owner.setId(ownerId);
		this.owners.save(owner);
		logger.info("Owner updated: id={}, lastName={}", ownerId, owner.getLastName());
		redirectAttributes.addFlashAttribute("message", "Owner Values Updated");
		return "redirect:/owners/{ownerId}";
	}

	/**
	 * Custom handler for displaying an owner.
	 *
	 * <p>
	 * 【中文补充说明】GET /owners/{ownerId} —— 主人详情页（含宠物与就诊记录）。 从仓库按主键加载主人（不存在抛
	 * {@code IllegalArgumentException}），放入 ModelAndView 后渲染 ownerDetails.html 模板。注意：由于类级
	 * {@link #findOwner} 已在本方法前加载过一次同 id 主人，此处的 {@code findById} 属于同一请求内的
	 * 重复查询（模板实际使用本方法放入的 owner 对象）。
	 * </p>
	 * @param ownerId the ID of the owner to display —— 待展示主人的主键 （{@code int} 类型，来自 URL
	 * 路径）
	 * @return a ModelMap with the model attributes for the view —— 包含 "owner" 属性、 视图为
	 * {@code "owners/ownerDetails"} 的 {@link ModelAndView}
	 * @throws IllegalArgumentException 当指定 id 的主人不存在时抛出
	 */
	@GetMapping("/owners/{ownerId}")
	public ModelAndView showOwner(@PathVariable("ownerId") int ownerId) {
		ModelAndView mav = new ModelAndView("owners/ownerDetails");
		Optional<Owner> optionalOwner = this.owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));
		mav.addObject(owner);
		return mav;
	}

}
