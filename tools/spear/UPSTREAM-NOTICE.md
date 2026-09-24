# SPEAR upstream notice

Source: <https://github.com/BadgersMC/spear-plugin>, revision 2c91bae.
The upstream README declares the project licensed MIT. That snapshot does not include a standalone LICENSE file.
Vendored ears.mjs and .agents/skills/spear-* come from that snapshot. The project-specific state.mjs and architecture template adaptations are identified in their headers.

## Full notice and provenance

The following text is reproduced verbatim from the same snapshot's
`reference-implementations/ts-spear/LICENSE`:
<https://github.com/BadgersMC/spear-plugin/blob/2c91bae/reference-implementations/ts-spear/LICENSE>

The root README declares MIT, but supplies no separate copyright notice for the
vendored helpers/skills. This preserves the upstream notice that is available;
it does not assert independently verified authorship of those helpers or resolve
the scope of the nested notice. Retain this provenance if distributing them.

```text
MIT License

Copyright (c) 2026 BadgersMC

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

Local Tags changes additionally consolidate the state and EARS entry points and add regression coverage.
