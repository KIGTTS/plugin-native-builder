# 原生构建扩展

扩展的源码、工具链校验、构建调度和专用包签名采用 MIT。
发布包不携带 ECJ、Kotlin 编译器、D8、Android 平台 JAR 或第三方项目依赖；
编译器通过用户确认的独立工具链包加载，其作者许可证必须随工具链分发。
编译器和依赖文件始终留在本扩展的 plugindata 子目录，不链接到主程序。

实际批处理调用依据 Eclipse 官方 BatchCompiler、JetBrains Kotlin CLICompiler 和 Google D8 API。
测试依赖仅供桌面 JVM 构建回归，不是 Android 工具链兼容性的证明。
