package com.starfield.api.dto;

/**
 * 作者选项响应，供前端搜索框的作者联想与上传/编辑表单的作者下拉共用
 *
 * @param name  作者名称
 * @param count 该作者名下的作品数
 */
public record CreationAuthorResponse(String name, Long count) {}
