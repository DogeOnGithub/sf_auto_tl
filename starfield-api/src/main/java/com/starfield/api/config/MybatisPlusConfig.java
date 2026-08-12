package com.starfield.api.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 自动填充处理器，处理 createdAt 和 updatedAt 字段
     */
    @Component
    public static class AutoFillHandler implements MetaObjectHandler {

        /**
         * 插入时自动填充 createdAt 和 updatedAt
         */
        @Override
        public void insertFill(MetaObject metaObject) {
            var now = LocalDateTime.now();
            this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
            this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        }

        /**
         * 更新时自动填充 updatedAt
         * <p>这里不能用 strictUpdateFill：它内部走 strictFillStrategy，只在字段值为 null 时才填充。
         * 而更新场景下实体几乎都是 selectById 载入的，updatedAt 非空，导致「更新时刷新 updated_at」
         * 这个能力从未真正生效——线上表现是编辑过的 Prompt 不会按更新时间排到列表最前，
         * 任务卡片显示的「更新时间」实际是创建时间。改为无条件覆盖。
         */
        @Override
        public void updateFill(MetaObject metaObject) {
            this.setFieldValByName("updatedAt", LocalDateTime.now(), metaObject);
        }
    }
}
