package com.allog.allogbe.routineverification.media;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 영상의 특정 시점에서 프레임 1장을 이미지로 추출하는 포트.
 * 실패 시 {@link FrameExtractionAttemptException} 을 던진다 (호출자가 재시도 여부를 결정).
 */
public interface VideoFrameExtractor {

	Path extractFrame(Path videoPath, Duration timestamp);
}
