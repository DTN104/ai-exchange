# AI Exchange Scaffold Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hoàn thành milestone A: public repository chứa Java/Spring Boot và Next.js scaffold build được, có kiểm tra kết nối thực, contract và hướng dẫn đóng góp.

**Architecture:** Maven monorepo với ba module Java độc lập Spring (exchange-core, simulation, journal) và một Spring Boot application. Next.js hiển thị tình trạng backend qua server-side fetch tới localhost. Không triển khai matching, account balances, agents hoặc journal trong milestone này.

**Tech Stack:** Java 21, Maven Wrapper dùng Maven 3.9.x, Spring Boot stable, Node.js 22, Next.js App Router, React, TypeScript, ESLint và GitHub Actions.

**Spec:** [Thiết kế đã duyệt](../specs/2026-10-19-ai-exchange-design.md), mục 8 phần scaffold và milestone A ở mục 9. Các yêu cầu MVP đầy đủ ở mục 3–6 được giữ cho milestone B–E; scaffold không tuyên bố đã đáp ứng chúng.

## Global Constraints

- Java 21 và Node.js 22, native ARM64; không yêu cầu Docker hoặc cloud.
- Core Java không phụ thuộc Spring, HTTP hoặc database.
- OpenAPI là nguồn chính; không tạo Postman workspace/cloud không cần thiết.
- Không sử dụng snapshot hoặc latest trong build đã commit; commit npm lockfile và Maven Wrapper.
- Bind localhost; public source repository không đồng nghĩa triển khai server public.
- License Apache-2.0; không sao chép/port mã ba dự án tham khảo trong milestone A.
- Không commit data/, credentials, dependency caches, research cache, browser logs hoặc file .playwright-mcp/ hiện có.
- Giữ nguyên hai tài liệu nghiên cứu/thiết kế; cập nhật trạng thái và liên kết khi cần.

## Review Focus

1. Backend chưa chạy: dashboard hiển thị “Backend unavailable”, có hướng dẫn khởi động; không dùng dữ liệu demo giả.
2. Backend trả sai JSON hoặc non-2xx: không hiển thị trạng thái connected; kiểm tra nhánh này bằng một HTTP test fixture nhỏ.
3. Clone sạch trên Linux CI: wrapper, lockfile và lệnh README đủ để build, không phụ thuộc Maven/global package trên máy tác giả.
4. Public repository: chỉ đưa các file dự án đã duyệt vào commit; kiểm tra staged paths và credential patterns trước khi push.
5. API ngoài scope: OpenAPI chỉ mô tả endpoint đã có; /runs và engine phải được đánh dấu planned trong roadmap, không giả thành tính năng hiện tại.

## File structure

- Root: pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties — Maven reactor và wrapper chính thức.
- modules/{exchange-core,simulation,journal}/pom.xml và README.md — module boundaries; README ghi rõ chưa có runtime implementation, không tạo interface rỗng cho tương lai.
- apps/server/pom.xml, src/main/java/io/aiexchange/server/{ExchangeApplication,StatusController}.java, src/main/resources/application.properties — backend health và trạng thái scaffold.
- apps/server/src/test/java/io/aiexchange/server/StatusApiTest.java — kiểm tra HTTP bằng JDK HttpClient với Spring random port.
- api/openapi.yaml — OpenAPI 3.1 contract cho health và status đang hoạt động.
- apps/dashboard/{package.json,package-lock.json,tsconfig.json,next-env.d.ts,next.config.ts,eslint.config.mjs} — cấu hình Next/TypeScript/lint.
- apps/dashboard/app/{layout.tsx,page.tsx,globals.css}, lib/backend-status.ts và tests/backend-status.test.ts — trang trạng thái, adapter fetch nhỏ, kiểm tra lỗi kết nối.
- scripts/smoke.mjs — assert HTTP trên hai process thật đã khởi động; chỉ dùng Node standard library.
- .github/workflows/ci.yml và dependabot.yml — kiểm thử build backend/frontend và dependency updates.
- README.md, CONTRIBUTING.md, SECURITY.md, LICENSE, THIRD_PARTY_NOTICES.md, docs/roadmap.md, scenarios/README.md — quickstart, license, phạm vi hiện tại và các milestone tiếp theo.
- .gitignore — thêm .playwright-mcp/, *.tsbuildinfo và cache phát sinh của tool nếu có.

## Task 1: Java scaffold với API trạng thái thật

**Interfaces:** GET /actuator/health trả 200 với status="UP". GET /api/v1/status trả 200 với {"name":"AI Exchange","stage":"scaffold","simulationAvailable":false}. Hai endpoint read-only, không auth khi chạy local. Chưa có API /runs.

- [ ] Kiểm tra registry và official compatibility docs, chọn patch stable cho Spring Boot hỗ trợ Java 21; pin Maven 3.9.x và wrapper chính thức với distribution checksum. Ghi phiên bản đã chọn trong README; không dùng milestone/RC.
- [ ] Viết api/openapi.yaml trước controller, gồm response schema, required fields và hai example đúng với interface trên. Không thêm các endpoint tương lai vào paths.
- [ ] Tạo parent reactor, ba module Java và server; dependency direction server → core/simulation/journal, simulation → core, journal → core. Module chưa có code được để trống với README giải thích trách nhiệm.
- [ ] Viết StatusApiTest: dùng @SpringBootTest(webEnvironment=RANDOM_PORT), JDK HttpClient; assert health.status==UP và status.name/stage/simulationAvailable đúng giá trị; assert content-type là JSON.
- [ ] Chạy ./mvnw -pl apps/server -am test, xác nhận test status thất bại khi chưa có controller.
- [ ] Thêm ExchangeApplication và StatusController với record response; application.properties bind 127.0.0.1:8080, chỉ expose actuator health, tắt banner và không thêm database dependency.
- [ ] Chạy ./mvnw verify; server tạo executable jar. Giới hạn heap runtime -Xmx512m trong quickstart, không tuyên bố đây là RSS đã đo.
- [ ] Commit contract, Maven files, module docs, implementation và test.

## Task 2: Next.js status dashboard và kiểm tra luồng HTTP

**Consumes:** GET http://127.0.0.1:8080/api/v1/status từ Task 1. **Produces:** trang / gồm tên dự án, stage, backend connection và các mốc tiếp theo; không giả lập order book hoặc trader đang chạy.

**UI đề xuất:** một trang nền sáng, chữ đậm dễ đọc, một thẻ kết nối backend và danh sách ngắn milestone B–E. Nội dung tiếng Anh để phù hợp repository open-source. Trang này là scaffold status page; thiết kế trading dashboard sẽ làm ở milestone E.

- [ ] Kiểm tra peerDependencies và Node engines; pin Next/React/TypeScript/ESLint tương thích, npm install tạo lockfile. Registry kiểm tra ngày 04/11/2026 cho next=16.4.5 (Node >=20.9), react=19.3.1; kiểm tra lại trước khi pin.
- [ ] Viết tests cho getBackendStatus(fetcher: typeof fetch): Promise<BackendStatus | null>, với BackendStatus chính xác là response Task 1. Assert response hợp lệ được chấp nhận; fetch throw, HTTP 503, JSON lỗi, stage sai hoặc simulationAvailable không phải false đều trả null.
- [ ] Dùng Node test runner và TypeScript stripping có sẵn trên Node 22; nếu runtime yêu cầu flag, ghi rõ trong test script. Chạy test và xác nhận fail trước implementation.
- [ ] Implement getBackendStatus bằng fetch tới URL localhost cố định, cache no-store, timeout 2 giây và kiểm tra schema nhỏ tại boundary; không thêm validation dependency.
- [ ] Implement server-rendered page / dùng adapter trên, force dynamic để trạng thái không bị đóng băng lúc build. Connected và unavailable có text rõ, không chỉ phân biệt bằng màu. Thêm link refresh với hành vi reload, không thêm polling/SSE khi chưa có simulation.
- [ ] Thêm npm scripts test, lint, typecheck, build và dev/start bind 127.0.0.1. Không tải font/CDN trong build.
- [ ] Chạy npm test, npm run lint, npm run typecheck và npm run build trong apps/dashboard.
- [ ] Viết scripts/smoke.mjs: dùng node:assert/strict và fetch timeout; assert health, status và HTML dashboard chứa “Backend connected”/“Simulation not implemented yet”. Chạy với Java jar và Next production server thật.
- [ ] Dừng backend rồi request lại dashboard, assert “Backend unavailable”; khởi động lại backend và kiểm tra connected trở lại để xác nhận không bị static cache.
- [ ] Commit dashboard, lockfile và smoke script.

## Task 3: Open-source docs, CI và public GitHub

**Consumes:** tất cả lệnh verify từ Task 1–2. **Produces:** public repository DTN104/ai-exchange, main branch có scaffold và CI đã kiểm tra.

- [ ] Thêm Apache-2.0 LICENSE đầy đủ; THIRD_PARTY_NOTICES phân biệt nghiên cứu tham khảo với code đã tái sử dụng (chưa có upstream code được port). Giữ các link license trong research.
- [ ] README có prerequisites, hai terminal khởi động server/dashboard, URLs, các lệnh verify và trạng thái rõ “Scaffold; simulation not implemented”. CONTRIBUTING mô tả clone/build/tests/license provenance; SECURITY hướng dẫn báo lỗi qua GitHub private vulnerability reporting nếu được bật, không bịa email liên hệ.
- [ ] docs/roadmap.md liệt kê B matching/risk, C journal/replay, D agents, E dashboard/benchmark; scenarios/README.md mô tả cấu hình tổng hợp sẽ có ở D, không tạo scenario mà chưa có loader.
- [ ] Thêm .github/workflows/ci.yml: Java 21 ./mvnw -B verify; Node 22 npm ci, tests, lint, typecheck, build; validate OpenAPI bằng validator version cố định. Chỉ permissions contents:read; không cần secret hoặc dịch vụ trả phí.
- [ ] Smoke CI khởi động executable jar và Next production server, bounded readiness polling, chạy script, stop backend và kiểm tra unavailable. Dọn process bằng trap kể cả job thất bại; lưu log khi có lỗi.
- [ ] Dependency update config dùng Maven/npm/GitHub Actions hằng tuần; không auto-merge. Checksum/pin action theo commit chính thức đã xác minh khi cấu hình.
- [ ] Chạy toàn bộ checks và smoke local. Kiểm tra git diff --check, staged file list và credential patterns; loại browser artifacts đang untracked, caches và data khỏi commit.
- [ ] Commit docs/CI; dùng gh kiểm tra lại DTN104/ai-exchange. Nếu chưa có, tạo --public với description; nếu đã có, kiểm tra nội dung trước và không overwrite. Push main sau khi xác minh remote đúng owner/tên/public visibility.
- [ ] Kiểm tra GitHub repo public, default branch main và CI run. Nếu CI fail, sửa nguyên nhân và chạy lại check liên quan; chỉ báo CI passed khi có kết quả run thành công.
- [ ] Kết quả giao: repository URL, commit SHA, local checks và CI thực tế; chưa benchmark M2 500 agent, chưa triển khai matching/replay và không deploy dịch vụ.

## Self-review và handoff

Kế hoạch chỉ triển khai milestone A đã được spec quy định; mục tiêu full MVP không bị xóa khỏi roadmap. Response schema nhất quán giữa controller, OpenAPI, dashboard và tests. Năm review focus ở trên có test hoặc kiểm tra thuộc task sở hữu. Không bổ sung trading API, placeholder engine implementation hay cloud setup.

Đề xuất thực thi Native: agent chính làm tuần tự trong chat này vì ba task phụ thuộc trực tiếp, scaffold nhỏ và ưu tiên ít chi phí. Cần chủ dự án review plan và chọn cách thực thi trước khi viết code theo writing-plans skill.
