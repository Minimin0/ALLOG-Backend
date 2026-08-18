# 배포

Ubuntu 22.04 + MySQL 8 동거 + systemd 기준. 실제로 이 순서로 서버를 올렸다.

## 이 디렉터리

| 파일 | 용도 |
|---|---|
| `allog.env.template` | 서버 환경변수 전체. 값을 채워 `/etc/allog/allog.env` 로 둔다 |
| `systemd/allog.service` | systemd 유닛. 시크릿 없음 |

**값이 채워진 `allog.env` 와 Firebase 서비스 계정 JSON 은 저장소에 두지 않는다.**
`.gitignore` 가 막고 있지만, 규칙보다 습관이 먼저다.

## 서버가 요구하는 것

```
Java 21            fat jar 126MB 를 java -jar 로 실행
MySQL 8 / InnoDB   기동 시 Flyway V1→V17 자동 적용, ddl-auto=validate 검사
RAM                4GB 권장 (MySQL 동거 기준). JVM 힙은 1g 로 제한한다
포트               8080. 리버스 프록시 뒤에 두고 127.0.0.1 에만 바인딩한다
```

## 순서

앞 단계가 실패하면 뒤는 진행하지 않는다.

### 1. 런타임

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk-headless mysql-server
sudo systemctl enable --now mysql
java -version && mysql --version
```

`openjdk-21` 이 저장소에 없으면 Adoptium 저장소를 추가해 `temurin-21-jdk` 를 쓴다.

### 2. 데이터베이스

```bash
read -rsp "allog_app 비밀번호: " APP_PW; echo
sudo mysql <<SQL
CREATE DATABASE IF NOT EXISTS allog CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'allog_app'@'localhost' IDENTIFIED BY '${APP_PW}';
GRANT ALL PRIVILEGES ON allog.* TO 'allog_app'@'localhost';
FLUSH PRIVILEGES;
SQL
unset APP_PW
```

`allog` 스키마에만 권한을 준다. 앱 런타임에 root 를 쓰지 않는다.

### 3. 배치

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin allog
sudo mkdir -p /opt/allog /etc/allog && sudo chown allog:allog /opt/allog

# 로컬에서 빌드 후 업로드
./gradlew bootJar
scp build/libs/allog-backend-0.0.1-SNAPSHOT.jar <계정>@<서버>:/tmp/

# 서버에서
sudo mv /tmp/allog-backend-0.0.1-SNAPSHOT.jar /opt/allog/app.jar
sudo chown allog:allog /opt/allog/app.jar && sudo chmod 644 /opt/allog/app.jar
```

`allog.env.template` 을 채워 `/etc/allog/allog.env` 로 두고 `chmod 600`, `chown allog:allog`.

### 4. 첫 기동은 포그라운드로

서비스로 감싸기 전에 로그를 눈으로 본다. **이때는 `FIREBASE_AUTH_ENABLED=false` 로 둔다** —
DB 문제인지 Firebase 문제인지 섞이지 않게 한다.

```bash
sudo -u allog bash -c 'set -a; . /etc/allog/allog.env; set +a; exec java -Xmx1g -XX:MaxMetaspaceSize=256m -jar /opt/allog/app.jar'
```

성공 신호:

```
Successfully applied 17 migrations to schema `allog`
Tomcat started on port 8080 (http)
Started AllogApplication in N seconds
```

검증 — **401 이 정상이다.** 헬스 엔드포인트가 없어서, 인증이 강제된다는 사실 자체가 살아있다는 신호다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8080/api/v1/routines   # 401
mysql -h 127.0.0.1 -u allog_app -p -N -B -e \
  "SELECT MAX(CAST(version AS UNSIGNED)), COUNT(*), SUM(success=0) FROM flyway_schema_history;" allog
```

### 5. Firebase

서비스 계정 JSON 을 `/etc/allog/firebase-service-account.json` 에 두고 `600`, `allog:allog`.
그다음 `FIREBASE_AUTH_ENABLED=true` 로 바꾸고 재기동한다.

JSON 의 `project_id` 는 프론트 `EXPO_PUBLIC_FIREBASE_PROJECT_ID` 와 **같은 프로젝트여야 한다.**
다르면 "로그인은 되는데 서버가 401" 이라는 가장 헷갈리는 증상이 나온다.

### 6. systemd

```bash
sudo cp deploy/systemd/allog.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now allog
sudo systemctl status allog --no-pager -l
sudo journalctl -u allog -n 50 --no-pager
```

자동복구 확인:

```bash
sudo systemctl kill -s SIGKILL allog && sleep 12 && sudo systemctl is-active allog
```

### 7. HTTPS

Android 는 평문 HTTP 를 기본 차단한다. 실기기 테스트 전에 필수다.

```
DNS A 레코드 확인 → nginx 설치 → ufw(22 먼저!) → 서버블록 → certbot → 갱신 확인
```

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp && sudo ufw --force enable
sudo certbot --nginx -d <도메인> --redirect
sudo certbot renew --dry-run
```

**ufw 는 22 를 허용한 뒤에 켠다.** 순서를 바꾸면 SSH 가 끊긴다.
**8080 은 열지 않는다** — 앱이 127.0.0.1 에만 묶여 있고 방화벽으로 이중 차단한다.

nginx 서버블록은 `proxy_pass http://127.0.0.1:8080` 에 `Host` / `X-Real-IP` /
`X-Forwarded-For` / `X-Forwarded-Proto` 를 넘기면 된다.
`client_max_body_size` 는 **1m 으로 충분하다** — 사진은 presigned URL 로 S3 에 직접
올라가므로 nginx 를 통과하지 않는다.

## 새 버전 배포

```bash
sudo systemctl stop allog
sudo cp /opt/allog/app.jar /opt/allog/app.jar.bak        # 롤백용
sudo mv /tmp/allog-backend-0.0.1-SNAPSHOT.jar /opt/allog/app.jar
sudo chown allog:allog /opt/allog/app.jar
sudo systemctl start allog
sudo journalctl -u allog -n 40 --no-pager | grep -E "Flyway|Started|ERROR"
```

새 Flyway 마이그레이션이 있으면 기동 시 자동 적용된다. 로그로 확인할 것.

## 자주 막히는 곳

| 증상 | 원인 |
|---|---|
| 기동 즉시 `IOException` / `FileNotFoundException` | `FIREBASE_AUTH_ENABLED=true` 인데 JSON 이 없거나 `allog` 계정이 못 읽음 |
| `FIREBASE_PROJECT_ID is required` | 위와 같은 상황에서 PROJECT_ID 가 빈 값 |
| `verification media bucket is required` | `VERIFICATION_MEDIA_ENABLED=true` 인데 6개를 다 안 채움 |
| `Access denied for user 'allog_app'` | `.env` 의 `DB_PASSWORD` 불일치 |
| `SchemaManagementException` | `ddl-auto=validate` 실패. **DB 를 손대지 말고 로그를 먼저 볼 것** |
| 설정을 고쳤는데 반영이 안 됨 | `.env` 가 CRLF. `grep -c $'\r' /etc/allog/allog.env` 가 0 이어야 한다 |
| 정상 종료가 실패로 기록됨 | 유닛에 `SuccessExitStatus=143` 누락 |

## 백업

MySQL 이 앱과 같은 장비에 있다. 덤프가 없으면 장비 장애 = 데이터 전손이다.

```bash
mysqldump -h 127.0.0.1 -u allog_app -p --single-transaction allog > allog_$(date +%F).sql
```
