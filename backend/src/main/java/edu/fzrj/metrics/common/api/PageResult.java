package edu.fzrj.metrics.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 分页响应（API 约定 6.2）
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    private List<T> items;
    private Long total;
    private Integer page;
    private Integer pageSize;
}
