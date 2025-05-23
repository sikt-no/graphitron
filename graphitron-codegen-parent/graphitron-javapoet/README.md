# Graphitron JavaPoet

This module contains an internal copy of the JavaPoet library, originally developed by Square, Inc. JavaPoet is a Java API for generating `.java` source files.

## About this Fork

This code is a direct copy of the [JavaPoet library](https://github.com/square/javapoet), with the package renamed from `com.squareup.javapoet` to `no.sikt.graphitron.javapoet`. We've made this fork to:

1. Remove the external dependency on `com.squareup:javapoet`, which is now archived and no longer maintained
2. Make JavaPoet an implementation detail of Graphitron, following advice from one of the JavaPoet authors: "Having the project as implementation detail removes a huge maintenance burden as you are not subject to maintaining strict API and ABI compatibility. It's an extremely low-cost option." ([Source](https://github.com/square/javapoet/discussions/866#discussioncomment-2137839))

This approach gives us full control to modify the code as needed while keeping it encapsulated within our project, similar to how JavaPoet itself started as an implementation detail in Google's Dagger.

We might replace this code with a different code generation library in the future, but for now, we are using JavaPoet as a convenient and well-tested solution.

## License

The original JavaPoet code is licensed under the Apache License 2.0. All files retain their original copyright notices, and this module includes a copy of the original license.