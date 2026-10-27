package edu.fzrj.metrics.common.page;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import edu.fzrj.metrics.common.error.ErrorCode;
import edu.fzrj.metrics.common.exception.BizException;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 分页查询参数：?page=1&pageSize=20&sort=createdAt:desc
 * sort 字段必须在调用方给定的白名单内，防注入。
 */
@Data
public class PageQuery {

    public static final int MAX_PAGE_SIZE = 100;

    private Integer page = 1;
    private Integer pageSize = 20;
    /** 格式：field:asc / field:desc */
    private String sort;

    public void setPage(Integer page) {
        this.page = (page == null || page < 1) ? 1 : page;
    }

    public void setPageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            this.pageSize = 20;
        } else {
            this.pageSize = Math.min(pageSize, MAX_PAGE_SIZE);
        }
    }

    /**
     * 转 MyBatis-Plus 分页对象
     *
     * @param allowedSortFields 允许排序的字段白名单（下划线列名）
     */
    public <T> Page<T> toPage(Set<String> allowedSortFields) {
        Page<T> mpPage = Page.of(this.page, this.pageSize);
        if (StringUtils.hasText(this.sort)) {
            String[] parts = this.sort.split(":");
            if (parts.length != 2) {
                throw new BizException(ErrorCode.PARAM_INVALID, "sort 格式应为 field:asc 或 field:desc");
            }
            String field = parts[0];
            String dir = parts[1].toLowerCase();
            if (!allowedSortFields.contains(field)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段: " + field);
            }
            if ("asc".equals(dir)) {
                mpPage.addOrder(OrderItem.asc(field));
            } else if ("desc".equals(dir)) {
                mpPage.addOrder(OrderItem.desc(field));
            } else {
                throw new BizException(ErrorCode.PARAM_INVALID, "排序方向只支持 asc / desc");
            }
        }
        return mpPage;
    }
}
