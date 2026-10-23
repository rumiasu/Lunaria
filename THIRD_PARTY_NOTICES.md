# Third-party components and notices

This file identifies the main components deliberately used by Lunaria. It is not an exhaustive legal audit of every transitive dependency. Exact resolved versions are in `backend/gradle.lockfile`; checksum metadata is in `backend/gradle/verification-metadata.xml`.

| Component | Resolved/selected version | Upstream license information |
|---|---|---|
| Gradle wrapper / distribution | 9.4.1 | Apache-2.0 and upstream distribution notices; copies in `licenses/Gradle-9.4.1-LICENSE.txt` and `licenses/Gradle-9.4.1-NOTICE.txt` |
| Spring Boot | 4.0.6 | [Apache-2.0](https://github.com/spring-projects/spring-boot/blob/v4.0.6/LICENSE.txt) |
| Spring Security | 7.0.5 | [Apache-2.0](https://github.com/spring-projects/spring-security/blob/main/LICENSE.txt) |
| MyBatis / Spring Boot starter | 3.5.19 / 4.0.1 | [Apache-2.0](https://github.com/mybatis/spring-boot-starter/blob/master/LICENSE) |
| H2 | 2.4.240 | [MPL-2.0 or EPL-1.0](https://www.h2database.com/html/license.html) |
| MySQL Connector/J | 9.7.0 | [GPLv2 with Universal FOSS Exception 1.0 and stated additional permissions](https://docs.oracle.com/cd/E17952_01/connector-j-9.7-license-gpl-en/licensing-information.html) |

The wrapper files retain upstream copyright/license headers. Their upstream Gradle distribution LICENSE and NOTICE are reproduced without modification in `licenses/`; that does not label Lunaria's business code as Gradle code or relicense this repository as a whole. Other listed libraries are dependency declarations, not copied library source trees. Redistribution of packages containing those libraries must retain applicable licenses/notices and satisfy each component's terms; MySQL Connector/J's exception conditions must be considered separately rather than assuming MIT/Apache terms apply to it.

No RuoYi source is incorporated, so its MIT license is not used as an umbrella license for this project. No WeChat Pay SDK is currently included. The referenced official SDK documentation is informational only. Repository-wide licensing for Lunaria's own code has not been selected here.
