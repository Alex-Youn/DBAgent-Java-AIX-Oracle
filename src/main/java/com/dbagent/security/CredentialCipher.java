package com.dbagent.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 대상 DB 접속 비밀번호의 암/복호화
 * (설계문서 `설계문서/비밀번호_암호화_설계_2026-09-21.md` 1~2절).
 *
 * <p>저장 포맷: {@code ENC(v1:<keyId>:<base64(IV 12B ‖ ciphertext ‖ tag 16B)>)}.
 * 기존 {@code B64(...)} 래퍼 관례를 그대로 계승해서, 비밀번호가 컬럼 하나가 아니라
 * {@code accounts} JSON 배열 안에도 들어 있는 구조를 같은 규칙 하나로 덮는다(별도 컬럼 추가 없음).
 * 버전(v1)과 키 ID를 포맷 안에 넣는 이유는 키 로테이션·마이그레이션 중간 상태·롤백에서
 * "이 값이 어떤 키로 잠겨 있는지"를 판별할 수 있어야 하기 때문이다.
 *
 * <p>AES/GCM/NoPadding, 암호화마다 {@link SecureRandom}으로 12바이트 IV를 새로 만들고 128비트
 * 인증 태그를 붙인다. <b>IV를 재사용하면 GCM에서는 키 복구로 이어지므로 상수 IV를 쓰면 안 된다.</b>
 * AAD로는 헤더 문자열({@code v1:<keyId>})을 넣어 버전/키ID 변조도 태그 검증에 포함시킨다.
 *
 * <p>복호화는 레거시 값을 그대로 통과시킨다 - {@code B64(...)}는 디코드, 그 외 평문은 그대로.
 * 마이그레이션이 중단되거나 일부만 끝난 혼재 상태에서도 앱이 정상 동작해야 하기 때문이다.
 *
 * <p>AIX(Java 8) 포팅을 위해 Java 8 문법만 사용한다.
 */
@Component
public class CredentialCipher {

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";
    private static final String B64_PREFIX = "B64(";
    private static final String B64_SUFFIX = ")";
    private static final String FORMAT_VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final MasterKeyProvider keyProvider;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCipher(MasterKeyProvider keyProvider) {
        this.keyProvider = keyProvider;
    }

    public String activeKeyId() {
        return keyProvider.activeKeyId();
    }

    /**
     * 항상 {@code ENC(v1:<활성 키>:...)} 형식을 만든다. null/빈 문자열은 그대로 통과 -
     * "비밀번호를 비워두면 기존 값 유지"라는 관리 화면 계약(updateInstance)과 충돌하지 않기 위함.
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        String keyId = keyProvider.activeKeyId();
        SecretKeySpec key = keyProvider.activeKey();
        byte[] iv = new byte[IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(keyId));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(cipherText, 0, packed, iv.length, cipherText.length);
            return ENC_PREFIX + FORMAT_VERSION + ":" + keyId + ":"
                    + Base64.getEncoder().encodeToString(packed) + ENC_SUFFIX;
        } catch (Exception e) {
            // 예외 메시지에 평문/암호문/키를 절대 싣지 않는다.
            throw new IllegalStateException("비밀번호 암호화에 실패했습니다(활성 키 " + keyId + ")", e);
        }
    }

    /**
     * {@code ENC(...)}는 해당 키로 복호화, {@code B64(...)}는 레거시 디코드, 그 외는 레거시 평문으로
     * 그대로 반환. 미지의 버전이나 이 서버에 없는 키 ID는 조용히 통과시키지 않고 예외를 던진다 -
     * 암호문을 그대로 DB 비밀번호로 써서 "왜 인증이 실패하는지 모르는" 상황을 만들지 않기 위함.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (isEncrypted(stored)) {
            return decryptEnc(stored);
        }
        if (stored.startsWith(B64_PREFIX) && stored.endsWith(B64_SUFFIX)) {
            String encoded = stored.substring(B64_PREFIX.length(), stored.length() - B64_SUFFIX.length());
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        }
        return stored;
    }

    /**
     * 활성 키로 암호화된 {@code ENC(v1:<활성 키>:...)}가 아니면 true - 평문/B64/구키/구버전 전부
     * 재암호화 대상이다. 기동 시 멱등 스캔(DatabaseConfigService.reencryptAllPasswords)이 이 판정을 쓴다.
     */
    public boolean needsReencrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return false;
        }
        if (!isEncrypted(stored)) {
            return true;
        }
        String[] parts = split(stored);
        if (parts == null) {
            return true;
        }
        return !(FORMAT_VERSION.equals(parts[0]) && keyProvider.activeKeyId().equals(parts[1]));
    }

    private boolean isEncrypted(String stored) {
        return stored.startsWith(ENC_PREFIX) && stored.endsWith(ENC_SUFFIX);
    }

    /** ENC(...) 안쪽을 [version, keyId, body]로 분리. 형식이 깨졌으면 null. */
    private String[] split(String stored) {
        String inner = stored.substring(ENC_PREFIX.length(), stored.length() - ENC_SUFFIX.length());
        String[] parts = inner.split(":", 3);
        return parts.length == 3 ? parts : null;
    }

    private String decryptEnc(String stored) {
        String[] parts = split(stored);
        if (parts == null) {
            throw new IllegalStateException("암호화된 비밀번호의 형식이 올바르지 않습니다(ENC(버전:키ID:값) 형태여야 함)");
        }
        String version = parts[0];
        String keyId = parts[1];
        if (!FORMAT_VERSION.equals(version)) {
            throw new IllegalStateException("알 수 없는 비밀번호 암호화 포맷 버전입니다: " + version);
        }
        SecretKeySpec key = keyProvider.keyFor(keyId);
        if (key == null) {
            throw new IllegalStateException("키 ID '" + keyId + "'가 이 서버의 마스터 키 파일에 없습니다 - "
                    + "다른 서버의 DB 파일을 키 파일 없이 가져왔거나, 로테이션에서 구키를 지운 경우입니다.");
        }
        byte[] packed;
        try {
            packed = Base64.getDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("암호화된 비밀번호가 base64 형식이 아닙니다(키 ID " + keyId + ")", e);
        }
        if (packed.length <= IV_LENGTH) {
            throw new IllegalStateException("암호화된 비밀번호의 길이가 올바르지 않습니다(키 ID " + keyId + ")");
        }
        byte[] iv = new byte[IV_LENGTH];
        byte[] cipherText = new byte[packed.length - IV_LENGTH];
        System.arraycopy(packed, 0, iv, 0, IV_LENGTH);
        System.arraycopy(packed, IV_LENGTH, cipherText, 0, cipherText.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(keyId));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 키가 다르거나(다른 서버에서 만든 값) 값이 변조된 경우 둘 다 여기로 떨어진다 -
            // 실측(2026-09-21 프로토타입): 두 서버가 키를 자동 생성하면 keyId가 똑같이 k1이라
            // "키 없음"이 아니라 이쪽 인증 태그 불일치로 실패하는 경우가 더 흔하다.
            throw new IllegalStateException("비밀번호 복호화에 실패했습니다(키 ID " + keyId
                    + ") - 이 서버의 키로 만든 값이 아니거나 값이 손상됐습니다.", e);
        }
    }

    private byte[] aad(String keyId) {
        return (FORMAT_VERSION + ":" + keyId).getBytes(StandardCharsets.UTF_8);
    }
}
