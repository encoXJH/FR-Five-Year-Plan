package edu.fzrj.metrics.common.datascope;

import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import edu.fzrj.metrics.security.LoginUser;
import edu.fzrj.metrics.security.SecurityUtils;
import org.springframework.stereotype.Service;

/**
 * 数据范围校验（API 约定 4.3）：
 * ADMIN / LEADER → 全校；SCHOOL_OWNER → 本人负责的指标；UNIT_OWNER → 本单元。
 * 越权抛 20005。
 */
@Service
public class DataScopeService {

    public boolean isAllData(LoginUser user) {
        return user.getRoles().contains("ADMIN") || user.getRoles().contains("LEADER");
    }

    public boolean isOwnerScope(LoginUser user) {
        return user.getRoles().contains("SCHOOL_OWNER");
    }

    public boolean isUnitScope(LoginUser user) {
        return user.getRoles().contains("UNIT_OWNER");
    }

    /** 要求全校数据范围（如平台配置、跨单元统计），否则 20005 */
    public void requireAllData() {
        if (!isAllData(SecurityUtils.getLoginUser())) {
            throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
        }
    }

    /** 校验对某单元数据的访问权；越权抛 20005 */
    public void canAccessUnit(Long unitId) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (isAllData(user)) {
            return;
        }
        if (isUnitScope(user) && unitId != null && unitId.equals(user.getUnitId())) {
            return;
        }
        // 校级项目负责人按指标归属控制，不按单元限制
        if (isOwnerScope(user) && !isUnitScope(user)) {
            return;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }

    /** 校验对某负责人名下指标的访问权；越权抛 20005 */
    public void canAccessIndicatorOwner(Long ownerId) {
        LoginUser user = SecurityUtils.getLoginUser();
        if (isAllData(user)) {
            return;
        }
        if (isOwnerScope(user) && ownerId != null && ownerId.equals(user.getUserId())) {
            return;
        }
        // 学院项目负责人通过本单元 B 任务访问，不按指标负责人限制
        if (isUnitScope(user) && !isOwnerScope(user)) {
            return;
        }
        throw new BizException(ErrorCode.DATA_SCOPE_DENIED);
    }
}
