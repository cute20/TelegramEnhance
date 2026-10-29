# 第三方依赖说明

本项目原创代码采用 Apache License 2.0。该许可证不替代第三方组件的许可证。

项目通过 Maven 和 pnpm 下载依赖，当前源码仓库不分发第三方 JAR、DLL、运行环境或构建后的前端依赖。依赖版本以 `pom.xml` 和 `frontend/pnpm-lock.yaml` 为准，许可证和版权声明以对应版本发布物及上游仓库为准。

主要依赖及上游：

- [Spring Boot](https://github.com/spring-projects/spring-boot)、[Spring Framework](https://github.com/spring-projects/spring-framework)
- [TDLight Java](https://github.com/tdlight-team/tdlight-java)：上游采用 GNU LGPL v3；[许可证](https://github.com/tdlight-team/tdlight-java/blob/master/LICENSE)
- [TDLight](https://github.com/tdlight-team/tdlight)、[Telegram TDLib](https://github.com/tdlib/td)
- [SQLite JDBC](https://github.com/xerial/sqlite-jdbc)、[SQLite](https://sqlite.org/)
- [JNA](https://github.com/java-native-access/jna)
- [Vue](https://github.com/vuejs/core)、[Vite](https://github.com/vitejs/vite)、[TypeScript](https://github.com/microsoft/TypeScript)

若另行分发编译后的应用及第三方库，需要同时保留所分发版本的许可证、版权声明和适用的源码或源码获取方式；LGPL 组件还需遵守其关于替换、重新链接及调试修改的相应要求。本项目不修改或重新授权这些依赖。
