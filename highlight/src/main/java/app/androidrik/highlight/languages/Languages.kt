package app.androidrik.highlight.languages

import app.androidrik.highlight.core.Language
import app.androidrik.highlight.languages.bash.bash
import app.androidrik.highlight.languages.c.c
import app.androidrik.highlight.languages.cmake.cmake
import app.androidrik.highlight.languages.cpp.cpp
import app.androidrik.highlight.languages.csharp.csharp
import app.androidrik.highlight.languages.css.css
import app.androidrik.highlight.languages.dart.dart
import app.androidrik.highlight.languages.diff.diff
import app.androidrik.highlight.languages.dockerfile.dockerfile
import app.androidrik.highlight.languages.go.go
import app.androidrik.highlight.languages.glsl.glsl
import app.androidrik.highlight.languages.ini.ini
import app.androidrik.highlight.languages.java.java
import app.androidrik.highlight.languages.javascript.javascript
import app.androidrik.highlight.languages.json.json
import app.androidrik.highlight.languages.kotlin.kotlin
import app.androidrik.highlight.languages.latex.latex
import app.androidrik.highlight.languages.lua.lua
import app.androidrik.highlight.languages.markdown.markdown
import app.androidrik.highlight.languages.php.php
import app.androidrik.highlight.languages.powershell.powershell
import app.androidrik.highlight.languages.properties.properties
import app.androidrik.highlight.languages.python.python
import app.androidrik.highlight.languages.rust.rust
import app.androidrik.highlight.languages.ruby.ruby
import app.androidrik.highlight.languages.sql.sql
import app.androidrik.highlight.languages.swift.swift
import app.androidrik.highlight.languages.typescript.typescript
import app.androidrik.highlight.languages.xml.xml
import app.androidrik.highlight.languages.yaml.yaml

/**
 * Every grammar bundled with the highlighter.
 *
 * Each entry builds a fresh mode tree: compilation mutates modes in place, mirroring `highlight.js`.
 */
internal fun builtinLanguages(): List<Language> = listOf(
    json(),
    ini(),
    cmake(),
    go(),
    glsl(),
    yaml(),
    bash(),
    dockerfile(),
    javascript(),
    typescript(),
    xml(),
    css(),
    dart(),
    java(),
    kotlin(),
    latex(),
    lua(),
    powershell(),
    properties(),
    python(),
    c(),
    cpp(),
    csharp(),
    sql(),
    diff(),
    markdown(),
    rust(),
    ruby(),
    php(),
    swift(),
)
