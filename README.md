# Slang Dictionary (Java Swing) 

Từ điển slang tiếng Anh (7.656 entry sau chuẩn hóa) viết bằng **Java 17+ / Swing thuần**, 10 chức năng của đề, dữ liệu lưu bằng file.

## Chạy

```
java -jar dist/SlangDictionary.jar
```

Cần JDK/JRE 17+. Thư mục `data/` nằm cạnh file JAR (đổi bằng `java -Ddictionary.home=/duong/dan -jar ...`). Thiếu file dữ liệu gốc thì app tự lấy bản đóng gói trong JAR.

| Việc | Lệnh |
|---|---|
| Build JAR không cần Maven | `./build.sh` (Windows: `build.bat`) |
| Chạy test không cần Maven | `JUNIT_CONSOLE=/path/junit-platform-console-standalone.jar ./build.sh test` |
| Maven (chưa chạy thử trong môi trường làm bản này) | `mvn clean verify` · `mvn verify -Pcoverage` (JaCoCo → `target/site/jacoco`); test GUI cần màn hình: `xvfb-run -a mvn clean verify` |
| Chuẩn hóa lại dữ liệu (hiếm khi cần) | `java -cp dist/SlangDictionary.jar dictionary.storage.NormalizeData .` |

## 10 chức năng

| # | Chức năng | Ở đâu |
|---|---|---|
| 1 | Tìm theo slang | Search → *By slang* (Enter = khớp chính xác; gõ tới đâu gợi ý prefix tới đó; hàng "Try:" gợi ý vài slang có thật) |
| 2 | Tìm theo definition | Search → *By definition* (substring, tô vàng từ khóa; hàng "Try:" gợi ý từ khóa phổ biến trong định nghĩa, và khi gõ dở thì gợi ý hoàn thành từ, vd. `laug` → laughing, laugh, laughter) |
| 3 | History | History (mới nhất trước, double-click để tìm lại, có nút xóa) |
| 4 | Thêm slang | Manage → *Add word*; trùng thì hỏi **Overwrite / Keep both / Cancel** |
| 5 | Sửa slang | Manage → *Edit* (hoặc double-click) |
| 6 | Xóa slang | Manage → *Delete* (có xác nhận) |
| 7 | Reset danh sách gốc | Settings → *Reset to original data* |
| 8 | Random slang | Discover → *Slang of the day* / *Surprise me* |
| 9 | Quiz: slang → 4 nghĩa | Quiz → *Guess the meaning* |
| 10 | Quiz: nghĩa → 4 slang | Quiz → *Guess the slang* |

Phím tắt: `Ctrl/Cmd+K` tìm nhanh, `Ctrl/Cmd+1..6` đổi màn hình, `1–4` trả lời quiz, `Enter` câu kế. Có giao diện sáng/tối.
Overwrite = thay **toàn bộ** nghĩa hiện có của slang đó bằng nghĩa mới; Keep both = thêm một entry mới (slang có nhiều nghĩa).

## Kiến trúc

```
ui/          Swing  + Tasks: luồng nền, MỘT luồng ghi, kết quả về EDT
service/     DictionaryService (nghiệp vụ, lưu + hoàn tác, history, phục hồi), QuizService, Benchmark
repository/  DictionaryRepository (HashMap + index trong bộ nhớ), Snapshot, CorruptDataException
storage/     FileStorage (MỌI thao tác file), SlangParser, NormalizeData, WorkingText
model/       SlangEntry (có ID), ParseIssue, ParseResult, ...
```

### Cấu trúc dữ liệu (giữ nguyên của B)

| Cấu trúc | Dùng để | Độ phức tạp |
|---|---|---|
| `HashMap<String, ArrayList<Long>>` slang (lowercase) → ids | tìm theo slang, một slang nhiều nghĩa | O(1) |
| `HashMap<Long, SlangEntry>` | truy cập/sửa/xóa theo ID | O(1) |
| `ArrayList<Long>` + `positions` | random, xóa swap-with-last | O(1) |
| `ArrayList<String>` sortedKeys (dựng lười) + binary search | gợi ý prefix | O(log n + k) |
| `lowerDefinition` cache trong entry | tìm definition chứa keyword | quét tuyến tính |

**Quy tắc ID:** ID gán tuần tự từ 1 theo thứ tự dòng khi parse, lưu cùng snapshot (`nextId` không bao giờ dùng lại); slang trùng hoàn toàn vẫn là hai entry khác ID.

### Mô hình đồng bộ (một mô hình duy nhất)

- Repository: mọi phương thức public `synchronized` (một monitor). **Không** dùng `ReadWriteLock` của A — ứng dụng desktop nhỏ, thao tác đọc rất ngắn, trộn hai cơ chế khóa chỉ thêm rủi ro.
- `DictionaryService`: thay đổi dữ liệu đi qua `writeMutex`; history có khóa riêng nên tìm kiếm không chờ việc lưu.
- `Tasks.write(...)`: **một luồng ghi duy nhất** (Add/Edit/Delete/Reset/xóa history) → thay đổi được áp dụng và lưu đúng thứ tự thao tác. `Tasks.run(...)`: pool đọc (tìm kiếm, nạp bảng, benchmark). Mọi callback chạy ở EDT; chỉ EDT được đụng Swing.
- Tìm kiếm trực tiếp (gợi ý, definition) debounce 180 ms, chạy nền, có số thứ tự truy vấn nên kết quả cũ không bao giờ ghi đè kết quả mới.
- Đóng app: shutdown hook chờ (tối đa 5 s) cho thao tác ghi đang chạy.

## Lưu trữ & phục hồi

```
data/original/slang.raw.txt          file gốc của đề — GIỮ NGUYÊN, app không bao giờ ghi
data/original/slang.normalized.txt   chuẩn hóa MỘT LẦN từ raw — app không bao giờ ghi; Reset đọc file này
data/original/malformed-report.txt   báo cáo từng dòng đã sửa / cách ly
data/working/dictionary.dat          snapshot: magic + format + VERSION ID + độ dài + CRC32 + HashMap/index
data/working/dictionary.dat.bak      snapshot hợp lệ liền trước
data/working/slang.working.txt       bản text đọc được, dòng đầu ghi cùng VERSION ID + số entry
data/working/history.tsv             lịch sử tìm kiếm
```

**Một lần lưu** = ghi working text version V (đọc lại xác minh) → xoay backup (chỉ khi biết chắc snapshot cũ hợp lệ) → ghi snapshot version V (đọc lại xác minh CRC). Mọi file ghi theo
*file tạm → fsync → đổi tên*. Thao tác chỉ được báo thành công khi **cả hai** file đã nằm trên đĩa; lỗi thì hoàn tác trong bộ nhớ, báo lỗi,
rồi cố ghi lại đĩa cho khớp bộ nhớ (version mới hơn).

**Kiểm tra dữ liệu nhập:** slang phải là một dòng — `\n`, `\r`, tab và mọi ký tự điều khiển bị **từ chối** kèm thông báo (không tự sửa), cũng như dấu `` ` ``, chuỗi rỗng, quá 100 ký tự, Unicode hỏng. Definition nhiều dòng được gộp thành một dòng. Dữ liệu hợp lệ luôn đọc lại giống hệt lúc ghi.

**Khi mở app**, chọn bản **mới nhất còn hợp lệ** trong {snapshot, working text, backup}:

| Tình huống | Kết quả |
|---|---|
| Snapshot và working text cùng version | nạp thẳng snapshot, **không index lại** |
| Snapshot hỏng (CRC, cắt cụt, index lệch) | dựng lại từ working text, cảnh báo, giữ bản hỏng ở `dictionary.dat.corrupt` |
| Working text mới hơn snapshot (crash giữa hai lần ghi) | dùng working text, không bao giờ trộn hai phiên bản |
| Snapshot mới hơn working text | dùng snapshot, làm mới working text |
| Snapshot + working text hỏng | dùng backup, cảnh báo có thể mất thay đổi gần nhất |
| Tất cả hỏng | **không tự reset**; giữ nguyên file, hỏi người dùng. Nếu họ đồng ý, file hỏng được dời sang `data/working-damaged-…` rồi nạp lại dữ liệu gốc |

## Parser và dữ liệu gốc

`SlangParser` chỉ sửa theo **quy tắc đã xác minh**, dòng nào lạ khác bị cách ly (QUARANTINED: không nạp, giữ nguyên văn trong báo cáo):

- 1 dòng header `Slag`Meaning` → ghi nhận, không nạp.
- 5 dòng nghĩa tiếp nối (413 AMA, 908 BOMB, 1112 C4C, 2004 ER, 2882 HOODIE): chỉ ghép khi **đúng cặp** (slang trước, câu tiếp nối) đã xác minh,
  vào nghĩa của dòng trước theo quy ước `| ` của chính file (vd. `AMA`Ask Me Anything| Against Medical Advice`) → app hiển thị thành hai nghĩa riêng.
- 2 ký tự hỏng encoding (`Æ`→`®`, `ô`→`™`) chỉ sửa khi phần nghĩa xác nhận ("Registered Trademark…", "Trademark").
- Khoảng trắng thừa (23 dòng) → cắt, có ghi báo cáo. 15 dòng trùng hoàn toàn → giữ nguyên, ghi báo cáo (xóa trong Manage nếu muốn).

Kết quả: 7.662 dòng → **7.656 entry**, 0 dòng cách ly, mọi dòng đều được tính (test `realRawFile_everyLineIsAccountedFor`).
File chuẩn hóa này **giống từng byte** với file chuẩn hóa độc lập của bản A. Lưu ý: bản B trước đây tách 5 dòng trên thành entry riêng (7.661 entry); bản hợp nhất thống nhất theo cách ghép của A vì khớp quy ước `|` của file.
Chế độ `WORKING` (đọc file do app ghi) không sửa gì để không đụng vào dữ liệu người dùng.

## Hiệu năng 100.000 từ

Số đo thật, **7 vòng**, máy sandbox 1 CPU (`docs/benchmarks/benchmark-100k.txt`; chạy lại bằng **Settings → Run 100,000-entry benchmark (3 rounds)**).
Add/Edit/Delete/Reset **đã gồm** thời gian lưu working text + snapshot + backup (có fsync) và bước đọc lại để xác minh:

| Thao tác | Min / trung vị / max |
|---|---|
| Tìm slang | 0,9 / 1,4 / 4 µs |
| Gợi ý prefix | 0,04 / 0,06 / 0,19 ms |
| Tìm definition (trung bình) | 2 / 2,4 / 7,6 ms (tệ nhất 67 ms) |
| Add (có lưu) | 217 / 253 / 529 ms |
| Edit (có lưu) | 200 / 325 / 365 ms |
| Delete (có lưu) | 193 / 214 / 242 ms |
| Reset (parse + index + lưu 100.000) | 382 / 454 / 827 ms |
| Khởi động lần đầu (chuẩn hóa + parse + index + lưu) | 475 / 596 / 1.881 ms |
| Khởi động lần sau (tái dùng index đã lưu) | 208 / 246 / 1.205 ms |

Vòng đầu luôn chậm nhất vì JVM chưa "nóng" (đó là cột max). Giao diện với 100.000 từ: độ đứng lớn nhất của luồng giao diện ≤ ~100 ms ở mọi thao tác đã đo.
**Chưa đo trên Windows**: tốc độ lưu phụ thuộc đĩa và hệ điều hành (fsync), hãy chạy benchmark trên máy của bạn trước khi quay video.

## Kiểm thử

**140 test JUnit 5** (`src/test/java/dictionary`): 121 test cũ đã chạy 0 lỗi; 19 test mới của `EdgeCaseTest` đã chạy qua một trình chạy thử thuần Java (máy làm bản này không tải được JUnit) và đều đạt — hãy chạy lại bằng `./build.sh test` hoặc `mvn clean verify`:

| Nhóm | Nội dung |
|---|---|
| `ValidationTest` | Slang chứa `\n`, `\r`, `\r\n`, tab, NUL, ESC, NEL, U+2028/2029 → **bị từ chối** (không tự sửa rồi cho qua), từ chối rỗng/chỉ khoảng trắng, dấu `` ` ``, quá dài, surrogate lẻ; Unicode hợp lệ (tiếng Việt, CJK, Ả Rập, Do Thái, emoji, tổ hợp dấu...) lưu rồi đọc lại **giống từng ký tự** qua cả hai đường (snapshot và working text); slang trùng; definition nhiều dòng được gộp thành một dòng |
| `PersistenceFaultTest` | Với MỖI thao tác (Add, Edit, Delete, Overwrite, Reset) và MỖI điểm ghi (trước khi ghi / trước khi đổi tên / sau khi đổi tên của working text, backup, snapshot): **(a)** tiến trình bị giết → mở lại luôn được một phiên bản nguyên vẹn (trước hoặc sau, không trộn), ghi tiếp được; **(b)** đĩa báo lỗi một lần → báo lỗi, hoàn tác bộ nhớ, đĩa khớp bộ nhớ; **(c)** đĩa chết hẳn → vẫn không hỏng. Phá từng file (rỗng, cắt đôi, rác, lật 1 byte, xóa), phá hai file, phá cả ba (báo lỗi rõ, không đụng file). Bước **xác minh đọc lại sau khi ghi** bắt được file ghi sai ngay lúc lưu |
| `FileStorageTest`, `DictionaryServiceTest` | CRC, cắt cụt, backup không bị bản hỏng đè, version ID chung, mọi kịch bản phục hồi, đọc/ghi đồng thời |
| `ParserTest`, `RepositoryTest`, `SearchAndQuizTest` | Parser (5 dòng thật, chuẩn hóa lặp lại được), chỉ mục, Quiz (đúng 1 đáp án, 4 phương án khác nhau) |
| `EdgeCaseTest` (19) | Trường hợp đặc biệt: Quiz đúng 4 đáp án khi dữ liệu vừa đủ; Quiz **báo lỗi rõ, không treo** khi không đủ 4 nghĩa/slang khác nhau, khi mọi entry cùng definition, khi một slang có nhiều nghĩa, khi nhiều slang cùng definition; Random với từ điển rỗng / chỉ 1 entry; Overwrite + Keep both sau khi mở lại; sửa thành slang đã có (index + prefix cập nhật); xóa đúng 1 entry trong nhiều slang trùng; Reset rồi mở lại; mất snapshot / working text / cả thư mục working / file normalized |
| `PerformanceTest` | 100.000 từ, 3 vòng đo, trung vị mỗi thao tác < 1 s (gồm lưu và Reset) |
| `GuiAcceptanceTest` (13) | Điều khiển cửa sổ thật: 10 chức năng (tìm slang/definition, History, Add + trùng Overwrite/Keep both, Edit, Delete có Cancel, Reset, Random, 2 kiểu Quiz), nhập slang `\r` bị chặn trong hộp thoại, đóng/mở lại app, **gõ nhanh 400 lần + đổi chế độ liên tục rồi kiểm tra kết quả cuối không bị kết quả cũ đè**, **đóng cửa sổ đúng lúc đang lưu** (JVM riêng) |
| `GuiLargeDataTest` (4) | 100.000 từ trong giao diện thật: đo độ đứng lớn nhất của luồng giao diện khi hiện 40.610 kết quả, gõ gợi ý, bảng Manage 100.000 dòng (nạp, lọc), và khi đang lưu |

Test GUI cần màn hình: máy không có màn hình thì tự bỏ qua; trên Linux dùng `xvfb-run -a mvn clean verify`. Đã **thử phá code có chủ ý** để chắc test không "xanh giả": bỏ hoàn tác khi lưu lỗi (5 test đỏ), bỏ bước xác minh sau khi ghi (1 đỏ), bỏ chặn ký tự điều khiển (14 đỏ), bỏ chờ lưu khi đóng cửa sổ (test đóng cửa sổ đỏ).

## Lỗi tìm được khi kiểm thử GUI và đã sửa

- Mỗi lần tìm (Enter) từng phải chờ ghi file history (có fsync) mới hiện kết quả — rất chậm trên macOS. Nay history lưu nền, không fsync; bản sao backup cũng bỏ fsync (snapshot và working text vẫn fsync).
- Hàng gợi ý "Try:" trước đây chỉ gợi ý slang kể cả khi đang tìm theo definition; nay theo chế độ đang chọn.
- Hiện 40.610 kết quả tìm theo definition làm giao diện **đứng ~480 ms** (Swing đo bề rộng từng dòng): nay cố định bề rộng ô và nạp model một lần → ~40 ms.
- Đóng cửa sổ khi đang lưu có thể bỏ dở lần lưu (dữ liệu vẫn không hỏng nhờ phục hồi, nhưng phải phục hồi): nay `windowClosing` chờ luồng ghi xong (tối đa 5 s) rồi mới thoát.
- Slang chứa xuống dòng trước đây bị tự đổi thành dấu cách; nay bị từ chối kèm thông báo.

## Cần tự làm / lưu ý trung thực

- **Nguồn tham khảo:** xem `NGUON_THAM_KHAO.txt` (đã liệt kê nguồn có thật); bạn tự điền tỷ lệ % cuối cùng.
- File chạy được nằm ở `dist/SlangDictionary.jar` (kèm `dist/data/`); đã mở thử từ thư mục sạch bằng JDK 21 + màn hình ảo.
- Quay video YouTube; điền họ tên, MSSV, % hoàn thành, **% code tham khảo / công cụ AI đã dùng** vào `DiemTuDanhGia.txt` (khai đúng sự thật); đẩy GitHub (≥ 10 commit rải nhiều ngày).
- **Đã chạy thật:** biên dịch `--release 17` trên JDK 21; 121 test qua JUnit Platform Console 1.9.1 (có và không có màn hình ảo); build JAR; chạy JAR từ thư mục giải nén sạch.
- **Chưa chạy được:** `mvn clean verify` — máy dùng để làm bản này không truy cập được Maven Central, nên `pom.xml` (JUnit 5.10.2, surefire 3.2.5, jar plugin 3.3.0, JaCoCo 0.8.12) và workflow CI **chưa được chạy thật**. Trước khi nộp hãy chạy `xvfb-run -a mvn clean verify` (Linux) hoặc `mvn clean verify` (Windows/macOS có màn hình) và xem tab Actions sau khi push. Nếu Maven lỗi cấu hình thì `./build.sh test` vẫn chạy được cùng bộ test.
- **Chưa thử trên Windows/macOS thật.** Test GUI chạy trên Linux + Xvfb (không có window manager), nên chưa kiểm tra giao diện khi kéo thay đổi kích thước bằng chuột thật.
- Dữ liệu `data/working` tạo bởi bản cũ (B hoặc A hoặc bản hợp nhất đầu tiên) **không dùng được** (định dạng mới): xóa thư mục `data/working` cũ một lần.
- Giới hạn đã biết: nếu ổ đĩa lỗi liên tục đúng lúc đang lưu, app báo lỗi và hoàn tác trong bộ nhớ, nhưng nếu lúc đó file working text mới đã kịp ghi thì lần mở sau sẽ thấy thay đổi đó (vẫn là một phiên bản nguyên vẹn, không mất hay hỏng dữ liệu).
