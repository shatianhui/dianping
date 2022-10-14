package com.hmdp.dto;

import lombok.Data;

@Data
public class UserDTO {
    // 删去了用户的敏感信息，避免泄漏风险
    private Long id;
    private String nickName;
    private String icon;
}
