package com.allog.allogbe.routineverification.storage;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * ⚠️ 임시 로컬 디스크 저장 구현체 — 연동 필요 지점: 실제 운영에서는 S3/GCS 등 오브젝트 스토리지로
 * 교체되어야 한다. 또한 원본 영상 파일 영구 저장 금지 원칙에 따라 보관 기간(TTL) 정책이 필요하지만
 * 아직 미정이다 (TODO, STAGE1/2 보고 참고).
 */
@Component
public class LocalTempRoutineVerificationMediaStorage implements RoutineVerificationMediaStoragePort {

	@Override
	public String store(MultipartFile file) {
		try {
			Path dir = Files.createDirectories(
					Path.of(System.getProperty("java.io.tmpdir"), "allog-routine-media"));
			Path target = dir.resolve(UUID.randomUUID() + extensionOf(file.getOriginalFilename()));
			file.transferTo(target);
			return target.toUri().toString();
		} catch (IOException e) {
			throw new MediaStorageException("미디어 저장 실패", e);
		}
	}

	private String extensionOf(String originalFilename) {
		if (originalFilename == null) {
			return "";
		}
		int dotIndex = originalFilename.lastIndexOf('.');
		return dotIndex >= 0 ? originalFilename.substring(dotIndex) : "";
	}
}
