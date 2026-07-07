Multisrc modules are shared theme implementations used by multiple extensions.

Build-ready modules are present for the anime-extensions themes so extensions can declare
`themePkg` and inherit multisrc versioning. Source code is ported per theme when its required
extractor dependencies exist in `:lib`.

Currently blocked by unported libraries:
- `anikototheme`: requires `m3u8server`
- `animekaitheme`: requires `megaupextractor`
- `dopeflix`: requires `dopeflixextractor`
- `pelisplus`: requires several unported extractors
- `yflixtheme`: requires `rapidshareextractor`
