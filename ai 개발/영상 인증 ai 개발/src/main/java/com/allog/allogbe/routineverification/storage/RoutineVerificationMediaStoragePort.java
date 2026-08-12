package com.allog.allogbe.routineverification.storage;

import org.springframework.web.multipart.MultipartFile;

/** 제출된 미디어 파일을 저장하고 접근 가능한 URL을 반환하는 포트. */
public interface RoutineVerificationMediaStoragePort {

	String store(MultipartFile file);
}
