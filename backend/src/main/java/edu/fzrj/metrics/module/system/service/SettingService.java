package edu.fzrj.metrics.module.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.module.system.dto.SystemDtos;
import edu.fzrj.metrics.module.system.entity.SysSetting;
import edu.fzrj.metrics.module.system.mapper.SysSettingMapper;
import edu.fzrj.metrics.module.system.service.support.SettingRules;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 系统参数（API 约定 15.10、PRD FR-M12-05）：查询与逐项修改（带 version 乐观锁）。
 * 参数键为平台预置（V2 种子），只允许修改值；valueType 按原类型校验；任一失败整体不落库。
 */
@Service
public class SettingService {

    private final SysSettingMapper sysSettingMapper;

    public SettingService(SysSettingMapper sysSettingMapper) {
        this.sysSettingMapper = sysSettingMapper;
    }

    public SystemDtos.SettingListView list() {
        List<SystemDtos.SettingItem> items = sysSettingMapper.selectList(new LambdaQueryWrapper<SysSetting>()
                        .eq(SysSetting::getDeleted, 0L)
                        .orderByAsc(SysSetting::getSettingKey))
                .stream().map(this::toItem).toList();
        return new SystemDtos.SettingListView(items, null, null);
    }

    @Transactional
    public SystemDtos.SettingListView patch(SystemDtos.SettingPatchRequest request) {
        // 先全量校验，任一失败整体不落库
        Map<String, SysSetting> current = sysSettingMapper.selectList(new LambdaQueryWrapper<SysSetting>()
                        .eq(SysSetting::getDeleted, 0L))
                .stream().collect(Collectors.toMap(SysSetting::getSettingKey, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        for (SystemDtos.SettingPatchItem item : request.getItems()) {
            SysSetting setting = current.get(item.getKey());
            if (setting == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "参数不存在：" + item.getKey());
            }
            String error = SettingRules.validateValue(setting.getValueType(), item.getValue());
            if (error != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, item.getKey() + "：" + error);
            }
            if (!setting.getVersion().equals(item.getVersion())) {
                throw new BizException(ErrorCode.VERSION_CONFLICT,
                        "参数 " + item.getKey() + " 已被他人修改，当前最新版本号：" + setting.getVersion());
            }
            before.put(item.getKey(), setting.getSettingValue());
            after.put(item.getKey(), item.getValue());
        }
        Long operator = SecurityUtils.getUserId();
        for (SystemDtos.SettingPatchItem item : request.getItems()) {
            SysSetting setting = current.get(item.getKey());
            setting.setSettingValue(item.getValue());
            setting.setUpdatedBy(operator);
            sysSettingMapper.updateById(setting);
        }
        return new SystemDtos.SettingListView(list().items(), before, after);
    }

    private SystemDtos.SettingItem toItem(SysSetting setting) {
        return new SystemDtos.SettingItem(setting.getId(), setting.getSettingKey(), setting.getSettingValue(),
                setting.getValueType(), setting.getDescription(), setting.getVersion());
    }

    /** 按 key 读参数值（不存在或已删除返回 defaultValue）；供业务模块读取平台预置参数 */
    public String getValue(String key, String defaultValue) {
        SysSetting setting = sysSettingMapper.selectOne(new LambdaQueryWrapper<SysSetting>()
                .eq(SysSetting::getSettingKey, key)
                .eq(SysSetting::getDeleted, 0L)
                .select(SysSetting::getId, SysSetting::getSettingValue));
        return setting == null ? defaultValue : setting.getSettingValue();
    }
}
