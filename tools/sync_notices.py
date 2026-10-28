from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[1]
items=[("project","本工程原创代码 · MIT","LICENSE"),("thirdparty","第三方组件来源与许可边界","THIRD_PARTY.md"),("notice","项目署名与致谢","NOTICE"),("mewx","MewX 上游 · GPL 2 或后续版本","licenses/mewx-GPL-2.0-or-later.txt"),("flutter-license","Wenku8_Flutter v0.4.8 · 原始 LICENSE","licenses/flutter-v0.4.8-LICENSE.txt"),("flutter-notice","Wenku8_Flutter v0.4.8 · 原始 NOTICE","licenses/flutter-v0.4.8-NOTICE.txt"),("flutter-gpl3","Wenku8_Flutter v0.4.8 · GPL 3","licenses/flutter-v0.4.8-GPL-3.0.txt"),("gradle","Gradle Wrapper · Apache License 2.0","gradle/wrapper/LICENSE")]
value={"items":[{"id":key,"title":title,"text":(ROOT/path).read_text(encoding="utf8")} for key,title,path in items]}
(ROOT/"app/src/main/assets/about-notices.json").write_text(json.dumps(value,ensure_ascii=False),encoding="utf8")
print("Synced local license notices: "+str(len(items)))
