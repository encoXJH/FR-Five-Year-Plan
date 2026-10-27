package edu.fzrj.metrics.module.plan.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.common.util.PatchHelper;
import edu.fzrj.metrics.module.plan.dto.PlanDtos;
import edu.fzrj.metrics.module.plan.entity.Plan;
import edu.fzrj.metrics.module.plan.entity.Template;
import edu.fzrj.metrics.module.plan.mapper.PlanMapper;
import edu.fzrj.metrics.module.plan.mapper.TemplateMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模板（API 约定 15.2）：列表 / 详情 / 修改 / 复制 / 停用 / 启用
 */
@Service
public class TemplateService {

    private final TemplateMapper templateMapper;
    private final PlanMapper planMapper;
    private final PlanService planService;

    public TemplateService(TemplateMapper templateMapper, PlanMapper planMapper, PlanService planService) {
        this.templateMapper = templateMapper;
        this.planMapper = planMapper;
        this.planService = planService;
    }

    public List<PlanDtos.TemplateItem> list() {
        return templateMapper.selectList(new LambdaQueryWrapper<Template>()
                        .eq(Template::getDeleted, 0L)
                        .orderByDesc(Template::getCreatedAt))
                .stream().map(this::toItem).toList();
    }

    public Template requireTemplate(Long templateId) {
        Template template = templateMapper.selectOne(new LambdaQueryWrapper<Template>()
                .eq(Template::getId, templateId)
                .eq(Template::getDeleted, 0L));
        if (template == null) {
            throw new BizException(ErrorCode.TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    public PlanDtos.TemplateDetail detail(Long templateId) {
        return toDetail(requireTemplate(templateId));
    }

    @Transactional
    public PlanDtos.TemplateDetail patch(Long templateId, PatchHelper patch) {
        Template template = requireTemplate(templateId);
        int version = patch.versionRequired();
        planService.requireVersion(template.getVersion(), version);
        if (patch.has("name")) {
            String name = patch.text("name");
            if (!StringUtils.hasText(name) || name.length() > 200) {
                throw new BizException(ErrorCode.PARAM_INVALID, "名称必填且不超过 200 字");
            }
            template.setName(name);
        }
        if (patch.has("description")) {
            // 可空字段：传 null 表示清空（API 约定 3.2）
            template.setDescription(patch.isNull("description") ? null : patch.text("description"));
        }
        template.setUpdatedBy(SecurityUtils.getUserId());
        if (templateMapper.updateById(template) == 0) {
            Template latest = requireTemplate(templateId);
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "数据已被他人修改，当前最新版本号：" + latest.getVersion());
        }
        return toDetail(requireTemplate(templateId));
    }

    @Transactional
    public PlanDtos.TemplateDetail copy(Long templateId, PlanDtos.CopyTemplateRequest request) {
        Template source = requireTemplate(templateId);
        Template copy = new Template();
        copy.setName(StringUtils.hasText(request == null ? null : request.getName())
                ? request.getName() : source.getName() + "（副本）");
        copy.setDescription(source.getDescription());
        copy.setContentJson(source.getContentJson());
        copy.setSourcePlanId(source.getSourcePlanId());
        copy.setIsEnabled(true);
        Long userId = SecurityUtils.getUserId();
        copy.setCreatedBy(userId);
        copy.setUpdatedBy(userId);
        templateMapper.insert(copy);
        return toDetail(requireTemplate(copy.getId()));
    }

    @Transactional
    public PlanDtos.TemplateItem disable(Long templateId) {
        return setEnabled(templateId, false);
    }

    @Transactional
    public PlanDtos.TemplateItem enable(Long templateId) {
        return setEnabled(templateId, true);
    }

    private PlanDtos.TemplateItem setEnabled(Long templateId, boolean enabled) {
        Template template = requireTemplate(templateId);
        template.setIsEnabled(enabled);
        template.setUpdatedBy(SecurityUtils.getUserId());
        templateMapper.updateById(template);
        return toItem(requireTemplate(templateId));
    }

    private PlanDtos.TemplateItem toItem(Template template) {
        String sourcePlanName = null;
        if (template.getSourcePlanId() != null) {
            Plan sourcePlan = planMapper.selectById(template.getSourcePlanId());
            sourcePlanName = sourcePlan == null ? null : sourcePlan.getName();
        }
        TemplateContent content = planService.parseContent(template.getContentJson());
        int directionCount = content.getDirections().size();
        List<TemplateContent.IndicatorNode> indicators =
                new java.util.ArrayList<>(content.getUnassignedIndicators());
        for (TemplateContent.DirectionNode direction : content.getDirections()) {
            indicators.addAll(direction.getIndicators());
        }
        int countA = indicators.size();
        int countAPlus = indicators.stream().mapToInt(indicator -> indicator.getChildren().size()).sum();
        Integer durationYears = content.getStartYear() != null && content.getEndYear() != null
                ? content.getEndYear() - content.getStartYear() + 1 : null;
        Long referenceCount = planMapper.selectCount(new LambdaQueryWrapper<Plan>()
                .eq(Plan::getTemplateId, template.getId())
                .eq(Plan::getDeleted, 0L));
        return new PlanDtos.TemplateItem(template.getId(), template.getName(), template.getDescription(),
                template.getSourcePlanId(), sourcePlanName, durationYears, directionCount, countA, countAPlus,
                referenceCount, template.getIsEnabled(), template.getVersion(),
                template.getCreatedAt(), template.getUpdatedAt());
    }

    private PlanDtos.TemplateDetail toDetail(Template template) {
        return new PlanDtos.TemplateDetail(template.getId(), template.getName(), template.getDescription(),
                planService.templateView(planService.parseContent(template.getContentJson())), template.getSourcePlanId(),
                template.getIsEnabled(), template.getVersion(), template.getCreatedAt(), template.getUpdatedAt());
    }
}
