package com.dbagent.security;

import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 대상 DB 접속 비밀번호를 암호화하는 마스터 키의 보관/로드 담당
 * (설계문서 `설계문서/비밀번호_암호화_설계_2026-09-21.md` 3절).
 *
 * <p>키 소스 우선순위: 환경변수 {@code DBAGENT_MASTER_KEY} → {@code dbagent.security.key-file}이
 * 가리키는 파일 → 그 파일이 없으면 <b>최초 기동 시 자동 생성</b>. 2026-08-18에 AES를 한 번
 * 구현했다가 "키 생성/서버별 환경변수 등록" 운영 부담 때문에 롤백한 이력이 있어
 * (`수정내역/커넥션풀_암호화_수정내역_2026-08-18.md` 56~64행), 이번에는 운영자가 아무것도 하지
 * 않아도 기동되도록 자동 생성을 기본 경로로 둔다.
 *
 * <p>키 길이는 이 JVM이 허용하는 최대치를 따른다 - 폐쇄망 IBM J9 Java 8은 무제한 정책 파일이
 * 없으면 AES-256을 거부할 수 있고, 인터넷이 안 되는 환경이라 현장에서 정책 파일을 받을 수도 없다.
 * 그래서 {@code getMaxAllowedKeyLength}를 조회해 256 미만이면 128비트로 자동 폴백한다(기동 실패
 * 방지). 반대로 키 파일에 든 키가 이 JVM에서 못 쓰는 길이면 조용히 넘어가지 않고 기동을 막는다 -
 * 전체 인스턴스가 이유 없이 죽는 것처럼 보이는 상황을 피하기 위함.
 *
 * <p>AIX(Java 8) 포팅을 위해 Java 8 문법만 사용한다(var/record/텍스트블록 금지).
 */
@Component
public class MasterKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(MasterKeyProvider.class);

    private static final String ENV_KEY = "DBAGENT_MASTER_KEY";
    private static final String DEFAULT_KEY_FILE = "dbagent.key";
    private static final String PROP_ACTIVE = "active";
    private static final String PROP_KEY_PREFIX = "key.";
    private static final String PROP_CREATED_PREFIX = "created.";

    @Value("${dbagent.security.key-file:}")
    private String keyFilePath;

    private String activeKeyId;
    private Map<String, SecretKeySpec> keysById;

    @PostConstruct
    void init() {
        String envValue = System.getenv(ENV_KEY);
        if (envValue != null && !envValue.trim().isEmpty()) {
            loadFromEnv(envValue.trim());
            return;
        }
        File file = new File(resolveKeyFilePath());
        Properties props = new Properties();
        if (file.exists()) {
            try (InputStream in = new FileInputStream(file)) {
                props.load(in);
            } catch (IOException e) {
                // 키를 못 읽으면 모든 인스턴스가 접속 불가가 되므로 조용히 넘어가지 않는다.
                throw new IllegalStateException("마스터 키 파일을 읽을 수 없습니다: " + file.getAbsolutePath(), e);
            }
        } else {
            generateInto(props);
            save(file, props);
            log.info("마스터 키 파일이 없어 새로 생성했습니다: {} - 이 파일이 유출되면 등록된 모든 대상 DB "
                    + "비밀번호가 복호화됩니다. 앱 디렉터리 밖(백업 스크립트 범위 밖)으로 옮기고 "
                    + "dbagent.security.key-file로 경로를 지정하는 것을 권장합니다.", file.getAbsolutePath());
        }
        loadFromProperties(props, file);
    }

    /** 현재 암호화에 사용할 키의 ID. 저장 포맷 ENC(v1:&lt;keyId&gt;:...)에 그대로 들어간다. */
    public String activeKeyId() {
        return activeKeyId;
    }

    /** 복호화용 - 해당 keyId의 키가 이 서버에 없으면 null(로테이션 전 구키/다른 서버 키 등). */
    public SecretKeySpec keyFor(String keyId) {
        return keysById.get(keyId);
    }

    public SecretKeySpec activeKey() {
        return keysById.get(activeKeyId);
    }

    private String resolveKeyFilePath() {
        return (keyFilePath == null || keyFilePath.trim().isEmpty()) ? DEFAULT_KEY_FILE : keyFilePath.trim();
    }

    /** 형식: {@code <keyId>:<base64 key>} (예: {@code k1:3q2+7w==}). */
    private void loadFromEnv(String value) {
        int idx = value.indexOf(':');
        if (idx <= 0) {
            throw new IllegalStateException(ENV_KEY + " 형식이 올바르지 않습니다 - '<keyId>:<base64키>' 형태여야 합니다.");
        }
        String keyId = value.substring(0, idx);
        byte[] raw = decodeKey(value.substring(idx + 1), ENV_KEY);
        checkKeyLengthAllowed(raw, ENV_KEY);
        Map<String, SecretKeySpec> keys = new HashMap<String, SecretKeySpec>();
        keys.put(keyId, new SecretKeySpec(raw, "AES"));
        this.activeKeyId = keyId;
        this.keysById = keys;
        log.info("마스터 키를 환경변수 {}에서 로드했습니다 (활성 키 {}, {}bit)", ENV_KEY, keyId, raw.length * 8);
    }

    private void loadFromProperties(Properties props, File file) {
        String active = props.getProperty(PROP_ACTIVE);
        if (active == null || active.trim().isEmpty()) {
            throw new IllegalStateException("마스터 키 파일에 " + PROP_ACTIVE + " 항목이 없습니다: " + file.getAbsolutePath());
        }
        active = active.trim();
        Map<String, SecretKeySpec> keys = new HashMap<String, SecretKeySpec>();
        for (String name : props.stringPropertyNames()) {
            if (!name.startsWith(PROP_KEY_PREFIX)) {
                continue;
            }
            String keyId = name.substring(PROP_KEY_PREFIX.length());
            byte[] raw = decodeKey(props.getProperty(name), file.getAbsolutePath());
            keys.put(keyId, new SecretKeySpec(raw, "AES"));
        }
        SecretKeySpec activeKey = keys.get(active);
        if (activeKey == null) {
            throw new IllegalStateException("마스터 키 파일의 활성 키(" + active + ")에 해당하는 "
                    + PROP_KEY_PREFIX + active + " 항목이 없습니다: " + file.getAbsolutePath());
        }
        checkKeyLengthAllowed(activeKey.getEncoded(), file.getAbsolutePath());
        this.activeKeyId = active;
        this.keysById = keys;
        // 키 값 자체는 절대 로그에 남기지 않는다 - 키 ID와 길이만.
        log.info("마스터 키 로드 완료 (활성 키 {}, 보유 키 {}개, {}bit)", active, keys.size(), activeKey.getEncoded().length * 8);
    }

    private void generateInto(Properties props) {
        int keyBytes = maxAllowedKeyBytes();
        byte[] raw = new byte[keyBytes];
        new SecureRandom().nextBytes(raw);
        props.setProperty(PROP_ACTIVE, "k1");
        props.setProperty(PROP_KEY_PREFIX + "k1", Base64.getEncoder().encodeToString(raw));
        props.setProperty(PROP_CREATED_PREFIX + "k1", LocalDate.now().toString());
    }

    /**
     * 이 JVM이 AES-256을 허용하면 32바이트, 아니면 16바이트. 폐쇄망 IBM J9 Java 8에서 무제한 정책
     * 파일이 없을 때 기동이 막히지 않도록 하는 폴백이다(설계문서 1-2절 R1).
     */
    private int maxAllowedKeyBytes() {
        int maxLen = maxAllowedKeyLength();
        if (maxLen >= 256) {
            return 32;
        }
        log.warn("이 JVM은 AES-256을 허용하지 않습니다(최대 {}bit) - AES-128 키로 생성합니다. "
                + "무제한 JCE 정책 파일을 설치하면 다음 키 생성부터 256bit를 쓸 수 있습니다.", maxLen);
        return 16;
    }

    private void checkKeyLengthAllowed(byte[] raw, String source) {
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("마스터 키 길이가 AES 규격(16/24/32바이트)이 아닙니다: "
                    + raw.length + "바이트 (" + source + ")");
        }
        int maxLen = maxAllowedKeyLength();
        if (raw.length * 8 > maxLen) {
            throw new IllegalStateException("이 JVM은 " + (raw.length * 8) + "bit AES 키를 쓸 수 없습니다(최대 "
                    + maxLen + "bit) - 무제한 JCE 정책 파일을 설치하거나, 키 파일을 지우고 재기동해 "
                    + "이 JVM이 허용하는 길이로 새 키를 생성하십시오(기존 암호문은 복호화 불가가 되므로 "
                    + "관리 화면에서 비밀번호를 다시 입력해야 합니다). 대상: " + source);
        }
    }

    private int maxAllowedKeyLength() {
        try {
            return Cipher.getMaxAllowedKeyLength("AES");
        } catch (Exception e) {
            // AES 자체가 없는 JVM은 사실상 없지만, 여기서 조용히 넘어가면 원인 파악이 어려워진다.
            throw new IllegalStateException("이 JVM에서 AES 지원 여부를 확인할 수 없습니다.", e);
        }
    }

    private byte[] decodeKey(String encoded, String source) {
        if (encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalStateException("마스터 키 값이 비어 있습니다 (" + source + ")");
        }
        try {
            return Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("마스터 키가 base64 형식이 아닙니다 (" + source + ")", e);
        }
    }

    private void save(File file, Properties props) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("마스터 키 파일을 만들 디렉터리를 생성하지 못했습니다: " + parent.getAbsolutePath());
        }
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, "DBAgent credential master key - git 커밋 금지 / dbconfig.db와 같은 매체에 백업 금지");
        } catch (IOException e) {
            throw new IllegalStateException("마스터 키 파일을 저장하지 못했습니다: " + file.getAbsolutePath(), e);
        }
        restrictPermissions(file);
    }

    /** POSIX(AIX/Linux)면 600으로 제한. Windows는 POSIX 뷰가 없어 건너뛴다(경고만). */
    private void restrictPermissions(File file) {
        try {
            if (!file.toPath().getFileSystem().supportedFileAttributeViews().contains("posix")) {
                return;
            }
            Set<PosixFilePermission> perms = new HashSet<PosixFilePermission>();
            perms.add(PosixFilePermission.OWNER_READ);
            perms.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (Exception e) {
            log.warn("마스터 키 파일의 권한(600) 설정에 실패했습니다 - 직접 확인하십시오: {} ({})",
                    file.getAbsolutePath(), e.getMessage());
        }
    }
}
