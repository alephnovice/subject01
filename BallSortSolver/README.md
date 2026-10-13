# Ball Sort Auto

แอป Android (Kotlin) แก้เกม Ball Sort อัตโนมัติ: กดปุ่มลอย 1 ครั้ง → อ่านจอ → คิดวิธีแก้ → แตะให้เอง

- `core/Vision.kt` อ่านแก้ว/ลูกบอลจากภาพ (รองรับกี่แก้ว กี่ลูกต่อแก้วก็ได้ ตรวจจากภาพจริง)
- `core/Solver.kt` weighted A* (รองรับย้ายทีละลูก/ย้ายทั้งแถว)
- `SolverService.kt` Accessibility service: ปุ่มลอย + screenshot + แตะ

## Build
- Android Studio: File > Open โฟลเดอร์นี้ → Run (ต้อง Android 11+)
- หรือ push ขึ้น GitHub แล้วโหลด APK จาก Actions (workflow ในโฟลเดอร์ `.github`)
