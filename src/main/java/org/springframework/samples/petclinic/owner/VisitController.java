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
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.validation.Valid;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 【中文补充说明】就诊记录 MVC 控制器：负责为指定主人的指定宠物新增就诊记录（Visit）。
 *
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 * <li>{@code @ModelAttribute("visit") loadPetWithVisit} 在每个 handler 前执行： 加载 Owner → 定位
 * Pet → 创建 Visit（默认日期=明天）并挂到 pet 上，同时把 pet/owner 放入模型，保证每次请求都基于<b>新鲜数据</b>（不使用 session
 * 缓存）；</li>
 * <li>表单最小日期约束由 {@code @ModelAttribute("minVisitDate")} 提供（明天）， 与 Visit
 * 实体默认值一致；提交后服务端再校验日期必须严格晚于当天；</li>
 * <li>落库以 Owner 为聚合根：{@code owner.addVisit(petId, visit)} + {@code owners.save(owner)}，经
 * Owner → Pet → Visit 级联链持久化；</li>
 * <li>{@code @InitBinder} 禁绑 {@code id}/{@code *.id}，防主键篡改。</li>
 * </ul>
 *
 * @author Juergen Hoeller
 * @author Ken Krebs
 * @author Arjen Poutsma
 * @author Michael Isvy
 * @author Dave Syer
 * @author Wick Dynex
 */
@Controller
class VisitController {

	/**
	 * SLF4J 日志记录器：记录就诊记录新增等写操作及校验失败（【中文补充说明】新增埋点）。
	 */
	private static final Logger logger = LoggerFactory.getLogger(VisitController.class);

	/**
	 * 主人仓库（Owner 聚合的持久化出口，就诊记录经其级联保存）。
	 */
	private final OwnerRepository owners;

	/**
	 * 构造器注入主人仓库。
	 * @param owners 主人仓库（{@link OwnerRepository} 类型），由 Spring Data JPA 代理提供实现
	 */
	public VisitController(OwnerRepository owners) {
		this.owners = owners;
	}

	/**
	 * 【数据绑定安全】注册禁绑字段：禁止表单请求参数绑定到 {@code id} 与任何嵌套的 {@code *.id} 属性，防止客户端篡改主键。
	 * @param dataBinder 当前请求的数据绑定器（{@link WebDataBinder} 类型）
	 */
	@InitBinder
	public void setAllowedFields(WebDataBinder dataBinder) {
		dataBinder.setDisallowedFields("id", "*.id");
	}

	/**
	 * Called before each and every @RequestMapping annotated method. 2 goals: - Make sure
	 * we always have fresh data - Since we do not use the session scope, make sure that
	 * Pet object always has an id (Even though id is not part of the form fields)
	 *
	 * <p>
	 * 【中文补充说明】{@code @ModelAttribute("visit")} 前置方法，在每个 handler 执行前运行： ① 按路径 ownerId
	 * 加载主人（不存在抛 {@code IllegalArgumentException}）； ② 按路径 petId 在主人名下定位宠物（找不到抛
	 * {@code IllegalArgumentException}）； ③ 将 pet 与 owner 放入模型供表单模板使用； ④ 创建新的
	 * {@link Visit}（构造器默认日期=明天）并立即挂到宠物的 visits 集合上 （等价于预先建立聚合内关系），该 Visit 作为表单命令对象返回给
	 * handler。
	 * </p>
	 * @param ownerId —— URL 路径变量主人主键（{@code int} 类型）
	 * @param petId —— URL 路径变量宠物主键（{@code int} 类型，须属于该主人）
	 * @param model 模型容器（{@link Map}<{@code String,Object}> 类型），本方法向其中 放入 "pet" 与 "owner"
	 * 属性
	 * @return Pet —— 新建的 Visit 实例（作为模型属性 "visit"）
	 * @throws IllegalArgumentException 当主人不存在或宠物在该主人名下不存在时抛出
	 */
	@ModelAttribute("visit")
	public Visit loadPetWithVisit(@PathVariable("ownerId") int ownerId, @PathVariable("petId") int petId,
			Map<String, Object> model) {
		Optional<Owner> optionalOwner = owners.findById(ownerId);
		Owner owner = optionalOwner.orElseThrow(() -> new IllegalArgumentException(
				"Owner not found with id: " + ownerId + ". Please ensure the ID is correct "));

		Pet pet = owner.getPet(petId);
		if (pet == null) {
			throw new IllegalArgumentException(
					"Pet with id " + petId + " not found for owner with id " + ownerId + ".");
		}
		model.put("pet", pet);
		model.put("owner", owner);

		Visit visit = new Visit();
		pet.addVisit(visit);
		return visit;
	}

	/**
	 * 【@ModelAttribute 前置方法】提供表单日期字段的最小可选日期（明天）， 供模板设置 HTML {@code min} 属性做前端约束。
	 * @return 明天（{@link LocalDate} 类型，{@code LocalDate.now().plusDays(1)}）
	 */
	@ModelAttribute("minVisitDate")
	public LocalDate minVisitDate() {
		return LocalDate.now().plusDays(1);
	}

	// Spring MVC calls method loadPetWithVisit(...) before initNewVisitForm is
	// called
	/**
	 * GET /owners/{ownerId}/pets/{petId}/visits/new —— 打开"新增就诊记录"表单页。
	 *
	 * <p>
	 * 表单上下文（pet/owner/visit/minVisitDate）均已由各 {@code @ModelAttribute} 前置方法准备就绪，本方法仅返回视图名。
	 * </p>
	 * @return 视图名 {@code "pets/createOrUpdateVisitForm"}
	 */
	@GetMapping("/owners/{ownerId}/pets/{petId}/visits/new")
	public String initNewVisitForm() {
		return "pets/createOrUpdateVisitForm";
	}

	// Spring MVC calls method loadPetWithVisit(...) before processNewVisitForm is
	// called
	/**
	 * POST /owners/{ownerId}/pets/{petId}/visits/new —— 处理"新增就诊记录"表单提交。
	 *
	 * <p>
	 * 流程：① 服务端日期校验：{@code visit.getDate()} 必须严格晚于当天，否则对 date 字段 reject
	 * {@code typeMismatch.visitDate}（消息键 "Visit date must be in the future"）；description
	 * 的必填校验由实体上的 {@code @NotBlank} + {@code @Valid} 完成； ② 校验失败 → 返回表单页（保留输入）； ③ 通过 →
	 * {@code owner.addVisit(petId, visit)}（内部含 Assert 防御）→ {@code owners.save(owner)}
	 * 经级联链持久化 → flash 成功消息 → 重定向回主人详情页（PRG 模式）。
	 * </p>
	 * @param owner 路径主人（{@link Owner} 类型，由 {@link #loadPetWithVisit} 放入模型）
	 * @param petId URL 路径变量宠物主键（{@code int} 类型）
	 * @param visit 表单绑定的就诊记录对象（{@link Visit} 类型）
	 * @param result 校验结果容器（{@link BindingResult} 类型）
	 * @param redirectAttributes 重定向属性（携带一次性 flash 消息）
	 * @return 校验失败返回 {@code "pets/createOrUpdateVisitForm"}； 成功返回
	 * {@code "redirect:/owners/{ownerId}"}
	 */
	@PostMapping("/owners/{ownerId}/pets/{petId}/visits/new")
	public String processNewVisitForm(@ModelAttribute Owner owner, @PathVariable int petId, @Valid Visit visit,
			BindingResult result, RedirectAttributes redirectAttributes) {
		if (visit.getDate() != null && !visit.getDate().isAfter(LocalDate.now())) {
			logger.warn("Visit date validation failed: ownerId={}, petId={}, date={}", owner.getId(), petId,
					visit.getDate());
			result.rejectValue("date", "typeMismatch.visitDate");
		}

		if (result.hasErrors()) {
			logger.warn("Visit creation validation failed: ownerId={}, petId={}, date={}", owner.getId(), petId,
					visit.getDate());
			return "pets/createOrUpdateVisitForm";
		}

		owner.addVisit(petId, visit);
		this.owners.save(owner);
		logger.info("Visit booked: ownerId={}, petId={}, visitId={}, description={}", owner.getId(), petId,
				visit.getId(), visit.getDescription());
		redirectAttributes.addFlashAttribute("message", "Your visit has been booked");
		return "redirect:/owners/{ownerId}";
	}

}
