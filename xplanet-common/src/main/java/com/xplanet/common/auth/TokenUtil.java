package com.xplanet.common.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 极简 Token 工具(自实现 HMAC 签名,不引第三方 JWT 库,便于讲清原理)。
 *
 * <h3>Token 结构</h3>
 * <pre>
 * payload = userId.过期时间戳
 * token   = base64(payload) + "." + base64(HMAC-SHA256(payload, secret))
 * </pre>
 * 校验时重新算签名比对,防止伪造;检查过期时间,防止长期有效。
 *
 * <p>这是 JWT 的简化版,核心思想一致:服务端不存 session,
 * 用签名保证 token 不可篡改(无状态鉴权,天然适合多实例水平扩展——
 * 任何实例都能独立校验 token,不需要共享 session)。
 *
 * <p>生产应直接用成熟 JWT 库(jjwt 等),这里手写是为了演示原理。secret 应放配置/密钥管理。
 */
public final class TokenUtil {

    private static final String SECRET = "xplanet-demo-secret-key-change-in-prod";
    private static final long EXPIRE_MS = 24 * 60 * 60 * 1000L; // 24小时

    private TokenUtil() {}

    /** 签发 token */
    public static String issue(long userId) {
        long exp = System.currentTimeMillis() + EXPIRE_MS;
        String payload = userId + "." + exp;
        String sig = sign(payload);
        return b64(payload) + "." + b64(sig);
    }

    /**
     * 校验并解析出 userId。失败(伪造/过期/格式错)返回 null。
     */
    public static Long verify(String token) {
        if (token == null || token.isEmpty()) return null;
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 2) return null;
            String payload = unb64(parts[0]);
            String sig = unb64(parts[1]);
            // 验签:重新计算签名比对,不一致说明被篡改
            if (!sign(payload).equals(sig)) return null;
            String[] pp = payload.split("\\.");
            long userId = Long.parseLong(pp[0]);
            long exp = Long.parseLong(pp[1]);
            if (System.currentTimeMillis() > exp) return null; // 过期
            return userId;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("sign failed", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }
}
