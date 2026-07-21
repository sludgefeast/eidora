## License

Eidora is free software, licensed under the **GNU General Public License v3.0
or later** (GPL-3.0-or-later). You are free to use, study, share and modify it,
provided that derivative works remain under the same license and their complete
source code is made available. See the [`LICENSE`](LICENSE) file for the full
terms.

```
Eidora — local, on-device face detection and recognition for your photos.
Copyright (C) 2026 Sebastian (Eidora contributors)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
```

### Machine learning models — separate, more restrictive terms

The GPL above covers **Eidora's own source code only**. The app itself ships
**without** any ML model. The face-detection and face-recognition models
(InsightFace SCRFD and ArcFace) are downloaded at runtime, after explicit user
consent, from the project's GitHub releases.

**These models are NOT under the GPL.** They originate from the
[InsightFace](https://github.com/deepinsight/insightface) project and are
licensed for **non-commercial research use only**. This means:

- You may use Eidora with these models for personal, non-commercial purposes.
- You may **not** use the models in a commercial product or service.
- The models' license applies independently of Eidora's GPL license.

See [`MODELS-LICENSE.md`](MODELS-LICENSE.md) for details. If you need a
commercially usable build, you must replace the models with ones whose license
permits your use case.
