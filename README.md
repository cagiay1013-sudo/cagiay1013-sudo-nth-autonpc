# NTH Auto NPC

Ứng dụng Android dùng AccessibilityService (chụp màn hình) + ML Kit OCR để tự tìm và chạm:
1. `Đối thoại`
2. `Đi tới truy nã`

## Yêu cầu
- Android 11 trở lên (API 30+).
- Chạy được trên JDK 8 (AGP 4.2.2 + Gradle 6.7.1 + compileSdk 33), phù hợp IDE trên điện thoại chỉ có Java 8.
- Internet lần đầu để Gradle tải thư viện.

## Cách build
1. Mở thư mục `NTH_AutoNPC` trong Android Studio/AndroidIDE (dự án có Gradle wrapper 6.7.1).
2. Chờ Gradle Sync xong.
3. Build > Build APK(s) — APK ở `app/build/outputs/apk/debug/app-debug.apk`.
   Hoặc dòng lệnh: `./gradlew assembleDebug`.

## Cài đặt và sử dụng
1. Cài APK, mở app NTH Auto NPC.
2. Bấm **MỞ CÀI ĐẶT TRỢ NĂNG** > NTH Auto NPC > Bật.
   - Android 13+ (cài APK ngoài Play Store): nếu công tắc bị mờ, vào Cài đặt > Ứng dụng > NTH Auto NPC > dấu ⋮ > **Cho phép cài đặt bị hạn chế**, rồi bật lại Trợ năng.
3. Mở game, đứng tại NPC có nút Đối thoại. Bảng nổi START/STOP hiện ở góc phải trên.
4. Bấm START: app chạm `Đối thoại`, rồi chạm `Đi tới truy nã`, sau đó tự dừng. STOP để dừng giữa chừng.

## Các lỗi đã sửa so với bản gốc
- Thiếu `import android.view.Display` → lỗi biên dịch.
- Bitmap từ `takeScreenshot` là hardware bitmap, ML Kit không đọc được (crash/không nhận chữ) → chuyển sang ARGB_8888.
- Chữ trên bảng nổi (“Đang tìm Đối thoại…”) chứa đúng cụm cần tìm → OCR có thể bấm nhầm vào chính bảng nổi; đã đổi chữ.
- Thiếu `<queries>` nên Android 11+ không tìm thấy game (nút MỞ GAME không chạy); thêm tìm theo tên app.
- Thiếu Gradle wrapper; thiếu cấu hình Java/Kotlin 17.
- Cấu hình trợ năng thừa (`flagRequestFilterKeyEvents` không có quyền tương ứng, `typeAllMask`).
- Giao diện bị đè lên thanh trạng thái khi targetSdk 35.
- Khoảng quét tăng 250 → 400 ms (Android giới hạn tần suất chụp màn hình); tự lùi lại khi bị báo quá nhanh.
- Khoảng chờ giữa 2 lần chạm được đặt ngay khi gửi cử chỉ, tránh chạm đúp.

## Lưu ý
- Game có thể chặn chụp màn hình (FLAG_SECURE) → màn hình sẽ đen, OCR không hoạt động.
- OCR chỉ nhận chữ Latin/tiếng Việt; chữ nằm trên nền phức tạp có thể đọc sai.
- Nếu `Đối thoại` xuất hiện nhiều nơi, app chạm vào cái đầu tiên tìm thấy.
- Nút MỞ GAME thử package `com.netease.nshm`, nếu không thấy sẽ tìm app có tên chứa “Nghịch Thủy Hàn”/“Justice”; không được thì mở game thủ công.
- Mã chưa được build thử trên thiết bị thật (môi trường sửa không có Android SDK).

## Bản v3
- Icon nổi tròn nhỏ: chạm để bật (▶ xanh) / tắt (■ đỏ), kéo để đổi vị trí.
- Mọi độ trễ đặt 0 ms (cấu hình ở đầu file NpcAccessibilityService.kt).
- Bật là tự chạy liên tục tối đa 13 giây (`runDurationMs`) rồi tự tắt; chạm icon để tắt sớm.
- Thêm bước chạm tên NPC (Vô thường 7 / Vô thường 8) trước khi bấm Đối thoại; đổi tên NPC ở `npcKeywords`.
- v6: "Đối thoại" và "Đi tới truy nã" được nhận dạng và chạm cùng lúc, không còn chia theo bước.

## Bản v7
- Tên NPC + Đối thoại + Đi tới truy nã: thấy cái nào chạm cái đó, tất cả trong 1 cử chỉ, cùng 1 lúc (0ms lệch nhau).
- Mọi độ trễ quét/thử lại = 0ms; thời gian giữ ngón tay 1ms (Android không nhận chạm 0ms).
- Danh sách tên NPC nhập ngay trong app (ô nhập + THÊM / XÓA), tự lưu, không cần sửa code. Mặc định chỉ còn "Vô thường 8".
- v7.1: chỉ quét vùng giữa màn hình gần nhân vật (40% rộng x 50% cao). Chỉnh `scanCenterX/Y`, `scanWidth/Height` ở đầu NpcAccessibilityService.kt.
- v7.2: tắt Kotlin daemon (`kotlin.compiler.execution.strategy=in-process`) vì daemon bị JVM crash (SIGSEGV) khi build trên điện thoại.
- v7.3: JVM crash cả trong Gradle daemon khi compileDebugKotlin -> thêm cờ JVM `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC`, 1 worker, không build song song.
- v7.4: giảm RAM build: -Xmx768m, Metaspace 256m, code cache 64m, tắt incremental/parallel/caching, Kotlin chạy in-process.
- v8: nút ⚙ trên màn hình mở khung chữ nhật kéo/đổi cỡ để chọn vùng quét (LƯU/✕). Bấm tên NPC (đầu) + thân NPC (offset px chỉnh trong app) cùng lúc với Đối thoại / Đi tới truy nã.
- v8.1: thêm .github/workflows/build.yml để build APK trên GitHub Actions khi JVM trên điện thoại bị crash.
- v9: vòng bấm tách khỏi vòng quét chữ: nhớ vị trí tên NPC/thân/Đối thoại/Đi tới truy nã rồi bấm liên tục cùng lúc, không chờ chụp màn hình + OCR (Android giới hạn chụp ≥ ~330ms/lần nên trước đây bị chậm). Nút ⚙ ẩn mặc định: giữ lâu nút ▶ để hiện/ẩn.
- v9.1: phím Giảm âm lượng bật/tắt; chạm vào icon tạm ngừng bấm để tắt được dù delay 0ms.
- v9.2: tự dừng khi bấm Home/Recents (launcher hiện) hoặc tắt màn hình bằng nút nguồn; phím Giảm âm lượng chỉ bị chặn khi đang chạy.
- v9.3: đang chạy thì chạm xuống nút nổi là tắt ngay (không chờ nhả tay).
- v9.4: bỏ phím âm lượng, tự dừng Home/tắt màn hình. Chỉ bật/tắt bằng nút nổi (chạm xuống là tắt) hoặc tự tắt sau 13s.
