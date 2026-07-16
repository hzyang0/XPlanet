package com.xplanet.api.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户服务对外暴露的基础资料。
 *
 * <p>跨服务接口使用稳定 VO，避免把数据库实体直接暴露给调用方。</p>
 */
@Data
public class UserProfileVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
}
