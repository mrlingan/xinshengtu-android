# Third-party references

This Android project is a new Kotlin / Jetpack Compose implementation inspired by
[CookSleep/gpt_image_playground](https://github.com/CookSleep/gpt_image_playground),
reviewed at commit `526a425d8817e256990387aa9afcc411aa3d6c8e` on 2026-11-01.
It is not an official Android release of the upstream project.

The upstream license is reproduced here for attribution:

```text
MIT License

Copyright (c) 2026 CookSleep

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Runtime dependencies include Kotlin / kotlinx.coroutines, AndroidX / Jetpack Compose,
OkHttp / Okio, and Coil, which retain their respective licenses. Dependencies are
resolved by Gradle and no third-party credentials are included.

The ChatGPT2API client adapter was checked against the HTTP interface of
[basketikun/chatgpt2api](https://github.com/basketikun/chatgpt2api) at commit
`92dd80e91543157d36a4ddad60e765c5293760e9` on 2026-11-09. The upstream Python
service is not bundled with this Android app. Users connect to their independently
deployed service with its configured auth-key.
