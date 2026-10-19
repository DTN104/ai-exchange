# Nghiên cứu nền tảng cho AI Exchange

Ngày kiểm tra: 19/10/2026. Phạm vi: README, license gốc, metadata GitHub và một số mã nguồn liên quan; chưa build hoặc benchmark các dự án upstream.

## Kết luận

Đề xuất một modular monolith Java 21/Spring Boot, lõi matching độc lập framework, agent runtime dùng thời gian mô phỏng và dashboard Next.js. Tham khảo thiết kế của ba dự án; chưa đưa mã nguồn hoặc thư viện của chúng vào sản phẩm. Chọn Apache-2.0 cho mã do AI Exchange phát triển sau khi chủ dự án duyệt.

Điểm khác biệt nên tập trung: thị trường nhiều agent có thể quan sát qua web, giải thích được một giao dịch đến từ đâu, và chạy lại thí nghiệm với bằng chứng hash. Không khẳng định ý tưởng này là độc nhất hoặc mô phỏng đã được hiệu chỉnh với thị trường thật.

## So sánh

| Dự án | Giá trị tham khảo | Giới hạn đối với MVP | Đề xuất |
| --- | --- | --- | --- |
| ABIDES gốc | Kernel discrete-event, message queue, thời gian ảo, độ trễ giữa agent và exchange; README nói hỗ trợ hàng chục nghìn agent | Python; mô hình phức tạp hơn nhu cầu ban đầu; không cung cấp sẵn Spring/Next dashboard | Học cách lập lịch, seed, message và tách agent khỏi exchange |
| ABIDES-JPMC | Tách Core / Markets / Gym; cấu hình sẵn noise, value, momentum, market maker | Repository đã archived khi kiểm tra; không nên phụ thuộc roadmap bảo trì | Tham khảo ranh giới module và cấu hình thí nghiệm |
| Bristol Stock Exchange (BSE) | Python đơn luồng, LOB tối giản cho giảng dạy; ZIC, SHVR, ZIP và các biến thể | README nêu giả định zero-latency; không phải hệ thống accounting/journal hoàn chỉnh cho dự án này | Học thí nghiệm cung/cầu, đối chứng chiến lược; không ghép runtime Python vào Java |
| exchange-core | Java; integer arithmetic; matching, risk, accounting; command journal, snapshots/replay; Disruptor | Dependency và build cũ; native affinity/JNA/Chronicle cần kiểm chứng ARM64/JDK21; REST gateway vẫn nằm trong danh sách việc cần làm của README | Nguồn tham khảo matching/risk và lựa chọn adapter ở giai đoạn đo hiệu năng sau |

ABIDES Kernel.py yêu cầu seeded NumPy RandomState, sử dụng PriorityQueue và thời gian giao sự kiện. Seed là điều kiện cần, không đủ bảo đảm tái lập khi thứ tự sự kiện, thuật toán PRNG hoặc phiên bản thay đổi. AI Exchange cần quy tắc tie-break và manifest riêng.

BSE README nói code gốc được giữ để tham khảo/legacy và dự kiến BSE2 ở repository riêng. Nghiên cứu này không xác minh BSE2 đã phát hành hay license của BSE2.

exchange-core README đưa ví dụ dependency 0.5.3; pom ở commit kiểm tra là 0.5.4-SNAPSHOT, Java 8, Disruptor 3.4.2, Chronicle Wire 2.19.1, affinity 3.2.2 và JNA 5.11.0. Không suy ra các phiên bản đó đã được kiểm chứng trên M2/Java 21.

## License đã đọc trực tiếp

| Nguồn | Nội dung xác minh | Nghĩa vụ khi tái phân phối mã |
| --- | --- | --- |
| ABIDES LICENSE.txt | BSD 3-Clause, copyright 2019 Georgia Tech Research Corporation; kèm funding acknowledgement và notices pandas/NumPy | Giữ copyright, conditions, disclaimer; binary cần tái hiện notices trong tài liệu; không dùng tên tác giả để quảng bá nếu chưa được phép |
| ABIDES-JPMC LICENSE | BSD 3-Clause, copyright 2021 J.P. Morgan Chase | Giữ notice/conditions/disclaimer; điều khoản không ngụ ý bảo trợ |
| BSE LICENSE.md | Văn bản MIT, copyright 2012 Dave Cliff | Giữ copyright và permission notice trong bản sao hoặc phần đáng kể |
| exchange-core LICENSE.txt | Apache License Version 2.0 | Kèm license, giữ attribution; đánh dấu file đã sửa; chuyển tiếp NOTICE liên quan nếu bản phân phối gốc có; có điều khoản patent grant/termination |

Tại thời điểm kiểm tra, GitHub API gắn NOASSERTION cho ABIDES và BSE. Đây là nhãn nhận diện tự động, không thay thế nội dung license đã đọc.

Các giấy phép trên cho phép dùng trong dự án open-source; chọn Apache-2.0 cho mã mới không đổi giấy phép của mã bên thứ ba. Nếu port thuật toán bằng cách chuyển trực tiếp mã Python sang Java, vẫn xử lý như mã phái sinh và giữ notices phù hợp. Ý tưởng tham khảo được ghi ở tài liệu nghiên cứu; mã sao chép/port phải được ghi thêm trong THIRD_PARTY_NOTICES cùng đường dẫn và commit nguồn. Dependency chuyển tiếp và dữ liệu trong repository phải được đánh giá riêng.

README của BSE còn đề nghị link về repository và cite bài Cliff (2018). ABIDES cung cấp bài Byrd et al. (2019), ABIDES-Gym có Amrouni et al. (2021). Nên giữ các dẫn nguồn học thuật khi dùng mô hình/thí nghiệm.

## Bảo trì và hiệu năng: giới hạn bằng chứng

GitHub metadata ngày kiểm tra:

| Repository | Archived | pushed_at (UTC) | HEAD nhánh mặc định |
| --- | --- | --- | --- |
| abides-sim/abides | false | 2023-07-06 | c4bf157678928934417aba6073eb0651aeaf6d15 |
| jpmorganchase/abides-jpmc-public | true | 2024-07-22 | f9cbe51342b7dedd9587e4e069040d68a5c6477f |
| davecliff/BristolStockExchange | false | 2025-04-13 | 6ebc4155440b07c6102c888040899e6a01383f43 |
| exchange-core/exchange-core | false | 2023-10-08 | 2f8548749839e9095c8dc597e4b61521d259fa5d |

pushed_at có thể phản ánh nhánh khác; không đồng nghĩa ngày HEAD hoặc ngày release. Không dùng số star làm bằng chứng về chất lượng/bảo trì.

Benchmark triệu lệnh/giây của exchange-core là tuyên bố upstream trên Intel Xeon/Java 8/Linux, với workload riêng và loại trừ network, IPC, journaling. Không thể áp số đó cho MacBook M2 hoặc hệ thống end-to-end của AI Exchange.

## Ba hướng kiến trúc

1. **Java thuần cho lõi MVP — đề xuất.** Kiểm soát event model/replay; ít dependency, dễ giải thích và kiểm thử. Đổi lại phải tự xây matching/accounting, nên bắt buộc kiểm thử invariant.
2. **Nhúng exchange-core.** Có sẵn matching/risk/persistence; có thể có lợi khi thực sự cần throughput lớn. Đổi lại cần thử nghiệm tương thích và thống nhất journal/replay, tránh hai nguồn dữ liệu gốc.
3. **ABIDES/BSE Python + Java gateway.** Bắt đầu nghiên cứu chiến lược nhanh; đổi lại hai runtime và giao tiếp liên tiến trình làm thứ tự sự kiện/tái lập khó hơn. Phù hợp phòng nghiên cứu sau MVP.

## Nguồn cố định theo commit

- [ABIDES README](https://github.com/abides-sim/abides/blob/c4bf157678928934417aba6073eb0651aeaf6d15/README.md), [license](https://github.com/abides-sim/abides/blob/c4bf157678928934417aba6073eb0651aeaf6d15/LICENSE.txt), [Kernel](https://github.com/abides-sim/abides/blob/c4bf157678928934417aba6073eb0651aeaf6d15/Kernel.py).
- [ABIDES-JPMC README](https://github.com/jpmorganchase/abides-jpmc-public/blob/f9cbe51342b7dedd9587e4e069040d68a5c6477f/README.md), [license](https://github.com/jpmorganchase/abides-jpmc-public/blob/f9cbe51342b7dedd9587e4e069040d68a5c6477f/LICENSE).
- [BSE README](https://github.com/davecliff/BristolStockExchange/blob/6ebc4155440b07c6102c888040899e6a01383f43/README.md), [license](https://github.com/davecliff/BristolStockExchange/blob/6ebc4155440b07c6102c888040899e6a01383f43/LICENSE.md), [BSE.py](https://github.com/davecliff/BristolStockExchange/blob/6ebc4155440b07c6102c888040899e6a01383f43/BSE.py).
- [exchange-core README](https://github.com/exchange-core/exchange-core/blob/2f8548749839e9095c8dc597e4b61521d259fa5d/README.md), [license](https://github.com/exchange-core/exchange-core/blob/2f8548749839e9095c8dc597e4b61521d259fa5d/LICENSE.txt), [pom](https://github.com/exchange-core/exchange-core/blob/2f8548749839e9095c8dc597e4b61521d259fa5d/pom.xml).
- [ABIDES paper](https://arxiv.org/abs/1904.12066), [BSE paper](https://arxiv.org/abs/1809.06027), [ABIDES-Gym paper](https://arxiv.org/abs/2110.14771).

## Kết quả thực hiện

Đã xác minh tài khoản GitHub DTN104 qua connector và CLI. Tên DTN104/ai-exchange trả HTTP 404 khi kiểm tra, phù hợp để thử tạo sau khi duyệt thiết kế; chưa đảm bảo tên vẫn trống ở thời điểm tạo.

Máy hiện có OpenJDK 21.0.10 ARM64, Node.js 22.23.3, npm, Git và gh. Maven chưa có trong PATH; scaffold nên kèm Maven Wrapper. Chưa chạy sản phẩm, chưa kiểm thử tương thích các framework upstream.
