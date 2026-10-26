# eMudhra案例（核对日期：2026-10-26）

Mozilla事件记录确认8组中间CA密钥存在极低熵问题；其原始证书与Sectigo交叉签名版本共16张，涉及TLS和代码签名。相同密钥可能对应不同证书指纹，因此规则同时匹配DER证书和SPKI指纹。公开证书取自原报告附件9664066/9664067；逐张核对事件记录中的SHA-256后计算SPKI，不包含私钥。

CA所述wrap/unwrap和脚本原因属于其调查声明；不要将根因声明与客户端实测混为一谈。中间CA失陷不代表根私钥也被恢复。同一根下其他证书的迁移影响也不等于所有密钥均已失陷。

客户端措施分属OneCRL、CRLSet、根库更新和各自策略；日期与版本不能用来代替设备实际状态。Google说明CT对执行相应策略的客户端提供额外保护，日志也已拒绝受影响链；不能推断任意TLS程序都执行CT，亦不能保证某张证书被任意客户端接受。

来源：
- Mozilla事件与原报告：https://bugzilla.mozilla.org/show_bug.cgi?id=2083449
- 公开证书附件：https://bugzilla.mozilla.org/attachment.cgi?id=9664066 和 https://bugzilla.mozilla.org/attachment.cgi?id=9664067
- Firefox响应：https://blog.mozilla.org/security/2026/10/20/protecting-firefox-users-following-the-compromise-of-emudhra-intermediate-certificates/
- Chrome/Android响应：https://blog.google/security/protecting-chrome-and-android-users-from-compromised-emudhra-intermediate-ca-certificates/
- CA调查声明：https://emudhra.com/en/post/certificate-security-incident
- Apple响应：https://support.apple.com/en-us/149555

内置规则为有日期的事件快照，不是实时吊销数据库。AndroidCAStore仅为一个可见证书库，应用自带根、网络安全配置、Google Play服务及浏览器拦截可改变实际验证结果。必要时提示更新对应软件/根库，不能未经验证就宣称整机安全或受到攻击。
