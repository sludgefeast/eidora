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

### Machine learning models — separate terms

The GPL above covers **Eidora's own source code only**. The app itself ships
**without** any ML model; models are downloaded at runtime, after explicit user
consent, from the project's GitHub releases.

Eidora ships one model set, and it is fully free:

- **Detection:** YuNet (Apache-2.0).
- **Embedding:** SFace (Apache-2.0).

**Both are Apache-2.0** — free for any use, including commercial — and these are
what the F-Droid build uses. No non-free model is part of Eidora or required for
it to work.

Eidora can also import a custom model container you build yourself, if you have a
model you're licensed to use — this is separate from the app and its free models.
Some models people build that way (e.g. InsightFace's SCRFD or ArcFace) are
non-commercial research use only; that restriction lives with those models, not
with Eidora, which never bundles or fetches them.

Each model carries two licenses (code and weights); the more restrictive
governs. See [`MODELS-LICENSE.md`](MODELS-LICENSE.md) for the full breakdown.
