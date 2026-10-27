package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.DictItem;
import edu.fzrj.metrics.module.system.mapper.DictItemMapper;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 自定义字典管理（API 约定 15.10、PRD FR-M12-04）：PUT 整体替换某字典类型的条目。
 * 请求中不存在的已有编码做逻辑删除；按 code 匹配更新或新增。
 */
@Service
public class DictAdminService {

    private final DictItemMapper dictItemMapper;

    public DictAdminService(DictItemMapper dictItemMapper) {
        this.dictItemMapper = dictItemMapper;
    }

    @Transactional
    public List<DictItem> replace(String dictType, SystemDtos.DictPutRequest request) {
        if (!dictType.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "字典类型只允许大写字母、数字、下划线");
        }
        Set<String> codes = new HashSet<>();
        for (SystemDtos.DictPutItem item : request.getItems()) {
            if (!codes.add(item.getCode().trim())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "字典编码重复：" + item.getCode());
            }
        }
        List<DictItem> existing = dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getDictType, dictType)
                .eq(DictItem::getDeleted, 0L));
        Map<String, DictItem> byCode = existing.stream()
                .collect(Collectors.toMap(DictItem::getCode, Function.identity()));

        Long operator = SecurityUtils.getUserId();
        Set<Long> keepIds = new HashSet<>();
        for (SystemDtos.DictPutItem item : request.getItems()) {
            String code = item.getCode().trim();
            DictItem current = byCode.get(code);
            if (current != null) {
                current.setLabel(item.getLabel().trim());
                current.setSort(item.getSort() == null ? 0 : item.getSort());
                current.setIsEnabled(item.getIsEnabled() == null || item.getIsEnabled());
                current.setUpdatedBy(operator);
                dictItemMapper.updateById(current);
                keepIds.add(current.getId());
            } else {
                DictItem created = new DictItem();
                created.setDictType(dictType);
                created.setCode(code);
                created.setLabel(item.getLabel().trim());
                created.setSort(item.getSort() == null ? 0 : item.getSort());
                created.setIsEnabled(item.getIsEnabled() == null || item.getIsEnabled());
                created.setCreatedBy(operator);
                created.setUpdatedBy(operator);
                dictItemMapper.insert(created);
                keepIds.add(created.getId());
            }
        }
        // 请求中未出现的既有编码 → 逻辑删除（deleted = 本行 id，配合唯一索引）
        for (DictItem stale : existing) {
            if (!keepIds.contains(stale.getId())) {
                stale.setDeleted(stale.getId());
                dictItemMapper.updateById(stale);
            }
        }
        return dictItemMapper.selectList(new LambdaQueryWrapper<DictItem>()
                .eq(DictItem::getDictType, dictType)
                .eq(DictItem::getDeleted, 0L)
                .orderByAsc(DictItem::getSort));
    }
}
