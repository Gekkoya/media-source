Checklist:

- [ ] Updated `extVersionCode` for individual extensions when behavior changed
- [ ] Updated `baseVersionCode` or `overrideVersionCode` for multisrc extensions when behavior changed
- [ ] Referenced related issues in the PR body
- [ ] Added `isNsfw = true` when the source is NSFW
- [ ] Kept source names and identifiers stable unless there is a migration reason
- [ ] Tested the changed extension locally
- [ ] Confirmed generated APKs do not package `compileOnly` dependencies
- [ ] Reviewed AI-assisted changes manually, if any
