# App files: private folders (`storage.files`) and files on the phone (`files`)

An app that declares the `storage.files` capability gets its own private folder on the phone, in addition to its 8 KiB `storage.project` JSON state and its camera photos. It can list, read, write, organize, move, copy and delete files there. No permission prompt or user gesture is involved: the folder belongs to the app alone. It is not visible to other PUB apps, other Android apps or the Files app, is never uploaded, is not sent to ChatGPT (edits receive only the app's code) and is deleted with the app.

Files anywhere else on the phone are reached only through the separate `files` hardware capability ([Files on the phone](#files-on-the-phone-files) below), with the user's permission, and only the files and folders the user picks in Android's system file picker.

The isolated JavaScript runtime still has no file access. The script describes each operation as an effect; the native host validates it, resolves the path inside the app's folder and replies with an event.

## Runtime contract

Validation lives in `core/.../FilesContract.java`; the model-facing text is `app/builder/FileGuide.kt`.

Request effect (counts toward the 4 effects per dispatch):

```
{type:"files.request", id, operation, args}
```

- Exactly these four fields. `id` is 1-64 of `[a-zA-Z0-9_-]` and is echoed in the reply (same rule as hardware ids). `operation` is one of `list stat read write mkdir delete move copy usage`. `args` is an object (`{}` when empty). An invalid envelope fails the UI packet, like a malformed hardware request.
- Arguments are validated when the host performs the request: unknown or mistyped arguments are an `invalid_arguments` reply and bad paths an `invalid_path` reply, so the app can recover.
- The host rejects the effect (the runtime stops with "Invalid UI packet") when the app did not declare `storage.files`.

Replies arrive later as events, in request order:

```
{type:"files", id, operation, ok:true, ...result}
{type:"files", id, operation, ok:false, code, message}
```

Codes: `not_found`, `exists`, `not_empty`, `is_directory`, `not_directory`, `invalid_path`, `invalid_arguments`, `quota_exceeded`, `read_only`, `unavailable`, `busy`. Messages are at most 200 characters. Every reply stays below 30,000 characters, inside the runtime's 32,768-character event limit.

At most 8 requests of a session may wait for their replies; more are answered at once with `busy`. While PUB saves a preview, new requests are also answered `busy`.

## Paths

Relative to the app's folder, `/`-separated and case-sensitive, with no leading or trailing `/` and no empty names. `""` or `"."` is the folder itself. Each name is 1-64 characters of ASCII letters, digits, space, `.`, `_`, `-`, `(` and `)`, and cannot start with `.` (so `.` and `..` are impossible and dot names stay reserved for the host). At most 8 names and 256 characters. A move or copy of a folder must also keep its contents within these limits.

## Operations

| Operation | Arguments | Result fields |
|---|---|---|
| `list` | `path?` (`""`), `recursive?` (false), `offset?` (0) | `entries:[{path,name,type,size,modified}]`, `total`, `truncated`, `nextOffset` when truncated |
| `stat` | `path` (the folder itself allowed) | `entry` |
| `read` | `path`, `offset?` (0), `length?` (1-12000, default 12000), `encoding` (`utf8`/`base64`) | `data`, `offset`, `bytesRead`, `size`, `eof` |
| `write` | `path`, `data`, `encoding`, `append?` (false), `createParents?` (true) | `size` |
| `mkdir` | `path` (creates missing parents) | `created` (false when it already existed) |
| `delete` | `path`, `recursive?` (false) | `deleted` (entries removed) |
| `move` | `from`, `to`, `overwrite?` (false) | `entry` of `to` |
| `copy` | `from`, `to`, `overwrite?` (false) | `entry` of `to` |
| `usage` | none | `bytes`, `files` (files and folders), `quotaBytes`, `quotaFiles`, `photos`, `photoBytes` |

- Entries: `type` is `file` or `directory`, `size` is bytes (0 for folders) and `modified` is milliseconds since 1970. Listings are sorted by path name by name, so a folder's contents follow it. One list reply holds at most 500 entries and fewer with long names; continue with `offset: nextOffset`.
- `read` returns at most 12,000 bytes. `base64` returns them all. `utf8` returns only whole, strictly valid UTF-8 characters and at most 24,000 characters after JSON escaping (counted conservatively), so `bytesRead` can be smaller than `length`; never a split character. Bytes that are not UTF-8 text at the offset are an `invalid_arguments` reply (read them as base64), as is a `length` too small for the next character. An offset at or past the end returns empty data with `eof:true`. The pure chunking logic is `FilesContract.utf8Prefix` / `readReply`.
- `write` data is at most 12,000 bytes after decoding (strict UTF-8 without unpaired surrogates, or strict base64). Without `append` the file is replaced atomically; with `append` the data is added at the end. A missing parent folder is created unless `createParents:false`, which answers `not_found`.
- `move` and `copy` create missing parent folders of `to`. An existing `to` is `exists` unless `overwrite:true`, which replaces a file with a file; a folder is never overwritten (`is_directory`), and a folder cannot replace a file (`not_directory`). `to` cannot be inside `from`.
- A path below a file is `not_found` for lookups and `not_directory` for changes.

## The photos folder

The root of every app's folder contains a virtual, read-mostly folder `photos` that lists the app's camera photos (from `ProjectMedia`, unchanged at `files/project-media/<id>/<uuid>.jpg`) as `photos/<uuid>.jpg` with their size and modification time. A photo app can therefore list `photos` at init and show every picture it took in earlier sessions with `{type:'image', asset:name}`.

- `list`, `stat` and `read` work on it; `delete photos/<uuid>.jpg` deletes the photo through `ProjectMedia.deletePhoto`, with its lock and containment rules.
- `copy` from `photos/` (one photo or the whole folder) is allowed and counts toward the files quota.
- `write`, `mkdir` below it, `move` into or out of it, copying into it and deleting the folder itself are `read_only`.
- In an edit preview the folder lists the preview's own captures (deletable) and the saved app's photos, which stay read-only as before.

## Limits

Per app: 256 MiB and 5,000 files and folders, separate from the photo quota (20 photos / 1 GiB). Quotas are checked before anything is written; a change that does not grow the folder is always allowed. A full phone is reported as `quota_exceeded`. Usage is counted once per folder and then kept current by every change.

## Storage and safety

`app/projects/AppFiles.kt` owns `files/app-files/`:

- `app-files/<id>/` per app, where `<id>` is a saved app ID (a UUID or the legacy `guitar-tuner`) or a `preview-<uuid>` identity. The folder is created on first write.
- Strict containment: script paths are validated names below the folder, every resolved location must equal its canonical form (a link anywhere on the way is `unavailable`), and tree walks use `lstat` and never descend into links. PUB never creates links.
- `app-files/.tmp/` holds the host's temporary files; script names can never start with `.`.
- No host change rewrites a file's existing bytes: every write replaces its file (temporary file, fsync, rename) and an append only adds bytes at the end. Exports therefore open the file and take its size under the lock, then stream exactly that many bytes without it, with no temporary copy.
- Copies and edit-preview snapshots are real copies. Android does not let apps create hard links (`link` is refused with "Permission denied" for app storage), so they take time and space in proportion to the files copied.
- One process-wide lock serializes all operations. Requests run in order on one dedicated background thread and replies are posted to the main thread.
- Each runtime start opens a new session. Closing, restarting or switching the app closes it (and bumps HostModel's files epoch, as for hardware): queued requests are skipped and late replies dropped.

## Lifecycle

- **New-app preview:** files go to `app-files/preview-<uuid>/`. **Save locally** moves them into the new app's folder last in `HostModel.savePreview`'s IO step, on the files thread after the preview's queued requests: one rename, else a staged copy published by rename, so all files arrive or none. A failed save rolls back the new app's folder with its package, data and photos.
- **Closing an unsaved preview** deletes its folder. **Startup** (once per process, before any request) removes `.tmp` contents, abandoned preview folders and folders of apps whose package no longer exists (a deletion cut short).
- **Edit preview:** starts with a copy of the saved app's folder (photos are not copied; the preview reads the saved app's photos read-only), made on the files thread before any of the preview's requests, so the saved originals never change. It is refused when it would leave less than 64 MiB free (cached data Android can clear counts as free, and is cleared first); the preview's file requests then answer `unavailable` with a message saying the phone's storage is too full. **Save changes** keeps the saved app's files and discards the preview's changes, the same as for its storage and photos; the preview banner says so.
- **Deleting an app** deletes its folder, after any request still running for it.
- The Tip Calculator sample is an ordinary saved app (no files until it uses them). Legacy `guitar-tuner` apps have a folder like any other.

Behavior checks and the visual review never touch real files. They answer `files.request` from `SimulatedFiles`, an in-memory folder that starts empty and behaves like this one (see `docs/behavior-checks.md`), and phone-file requests as if nothing were picked (pickers answer `cancelled`). The visual review captures the screen after the replies to init's requests, so it shows the app's empty state. The generation contract still tells apps to render a complete screen before any reply.

## Host API

`AppFiles(context, media, quotaBytes, quotaEntries)`, all blocking methods safe from any thread except the main thread:

- `open(Scope, deliver)` → `Session` with `request(effect, busy?)` and `close()` (main thread).
- `execute(Scope, effect)` → reply event.
- `snapshot(appId, previewId)`, `publishPreview(previewId, appId)`, `delete(id)`, `cleanupTemporary(isSaved?)`, `cleanupAtStartup(isSaved)`.
- `importStream(Scope, path, input, overwrite)` and `exportStream(Scope, path, output)` move bytes between a stream and the folder with the same rules and quotas, for host features that bring files in from, or hand them to, other places on the phone.
- `later { }` queues work on the files thread; `serial { }` runs it there and waits.

`Scope(folder, photos, savedPhotos)` names the folder, the media project whose photos `photos/` lists and deletes, and, for edit previews, the saved app whose photos are listed read-only.

## Verification

`FilesContractTest` and `FilesTextChunkTest` (JVM) cover the envelope, paths, every operation's arguments and bounds, encodings, error replies, list paging and the size of read and list replies. `AppFilesDeviceTest` (instrumentation, sandboxed `filesDir`) covers every operation, quotas, containment and links, the photos folder, preview save, edit-preview copy-on-write, deletion and cleanup; it has not yet been run on a phone.

## Files on the phone (`files`)

Apps can also work with documents and folders the user picks in Android's system file picker (the Storage Access Framework): files in the phone's shared storage and Downloads, and documents that other apps offer through document providers (for example Drive). "Files in other apps" means only what those apps expose to the picker, never their private storage. PUB requests no storage permission (no `MANAGE_EXTERNAL_STORAGE`, `READ_`/`WRITE_EXTERNAL_STORAGE` or `READ_MEDIA_*`): the picker grants access to exactly what the user picks.

`files` is a hardware-broker capability (`docs/hardware-runtime.md`). Settings → Access → **Files on this phone** has the global block, the default rule and per-app rules like every hardware group, and PUB's consent dialog appears the first time an app asks in a session (with Ask). Code: `core/.../PhoneFilesContract.java` (operations, arguments, replies) and `PhoneFileGrants.java` (grant file, holder count); `app/hardware/PhoneFiles.kt` (operations), `PhoneFileGrantStore.kt` (grants and Android URI permissions), `PhoneDocuments.kt` (DocumentsContract and ContentResolver adapters behind interfaces), `FilePickerContracts.kt` (picker intents); the model-facing text is the second part of `app/builder/FileGuide.kt`.

### What an app can see

- Only what the user picked: an opaque random handle (24 hex digits, 96 random bits; valid only for the app that holds it), the document's display name, MIME type, size and modification time, and inside a picked folder the names of its contents.
- Never content URIs, file system paths, provider names or authorities, or provider error text: every reply message is fixed host text.
- Nothing it was not given: no browsing, searching or listing of the phone, nothing above a picked folder, no other app's private data. Android itself does not let the folder picker choose the root of the storage, the Download folder itself or `Android/data`. Online-only documents (Google Docs and similar) have no bytes; the pickers offer openable documents only.

### Requests and replies

Requests are `{type:"hardware.request", id, capability:"files", operation, args}`. `HardwareContract` checks the envelope (for `files` the arguments may take up to 32,768 characters, so escaped write data fits); `PhoneFilesContract.parse` checks the arguments when the request is performed, so a bad argument is a recoverable `error` event (`invalid_arguments`, `invalid_path`) rather than a failed packet. Replies are `{type:"hardware", id, capability:"files", event, data}`; every data object stays below 29,700 characters.

| Operation | Arguments | Event: data |
|---|---|---|
| `pick` | `mode` `open`/`openMultiple`/`folder`/`create`; `mimeTypes?` (1-8, open modes; default all); `write?` (open modes and folder, default false); `max?` 1-20 (openMultiple); `name` and `mimeType?` (create) | `picked`: `items` [{`handle`, `name`, `kind` file/folder, `mimeType`, `size?`, `canRead`, `canWrite`}], `truncated`; or `cancelled` |
| `grants` | none | `grants`: `items` (same shape, no size), `max` (32) |
| `release` | `handle` | `released`: `handle` |
| `stat` | `handle`, `path?` (folders) | `entry`: `entry` {`name`, `path?`, `kind`, `mimeType`, `size?`, `modified?`, `canWrite`} |
| `list` | `handle` (folder), `path?`, `offset?` | `entries`: `entries`, `total`, `truncated`, `nextOffset?`, `incomplete?` |
| `read` | `handle`, `path?`, `offset?`, `length?` 1-12,000, `encoding` utf8/base64 | `data`: `data`, `offset`, `bytesRead`, `size?`, `eof` |
| `write` | `handle`, `path?`, `data`, `encoding`, `append?` | `written`: `size?`; in folders also `created`, `entry` |
| `mkdir` | `handle` (folder), `path` | `created`: `created`, `entry` |
| `delete` | `handle`, `path?` (folders), `recursive?` | `deleted`: `handle`, `released` (file handle) or `path` |
| `rename` | `handle` (folder), `path`, `name` | `renamed`: `entry` |
| `import` | `handle`, `path?`, `to` (private path), `overwrite?` | `imported`: `entry` (the private file's entry) |
| `export` | `from` (private path) and either `handle`, `path?`, `overwrite?` or `createName`, `mimeType?` | `exported`: `bytes`, plus `entry` (folder path) or `item` (new document) |

- **Gestures.** `pick` and `export` with `createName` open the picker: they need a user action and the broker's native-screen lock (`busy` while a picker or other native screen is open). `delete` always needs a user action: a script can never delete the user's documents from init or a callback. With Ask, the first files request of a session needs a tap so PUB can show its dialog. Once access is allowed (an Allow rule, or the user allowed it in this session) the other operations also work from init and from replies, so a saved app can reopen its documents when it starts.
- Before the picker opens, PUB checks that there is room for another grant (`grant_limit`) and, for an export, that the private file exists, so the user never picks a place for nothing.
- **Paths inside a picked folder** are the documents' real names: 1-255 characters without `/`, control characters, line or paragraph separators or unpaired surrogates, never `.` or `..`; at most 8 levels and 1,024 characters. Unlike private files, names may use any language and punctuation. Each name is looked up among the folder's children as the provider lists them (exact, case-sensitive match) and PUB never builds provider paths, so a path cannot leave the picked folder. A name that cannot be addressed (for example a cloud document whose name contains `/`) is listed without `path`; two children with the same name answer `unavailable`.
- `list` reads at most 10,000 children, sorts them by name (case-insensitively) and returns at most 500 entries and 29,700 characters per reply; `incomplete` marks a larger folder. Folders of cloud providers may still be loading; list again.
- `read` and `write` move at most 12,000 bytes per request with the private files' UTF-8 and base64 rules (`FilesContract.utf8Prefix`, strict decoding). Phone files are written in place by their provider: a replace truncates first (`"wt"`), an append uses `"wa"` where the provider supports it (else `unavailable`); there is no atomic replace. A document that cannot seek (a stream) is readable only within its first 64 MiB; import it instead.
- `write` and `export` into a folder create missing folders and the file (MIME type from the extension). The provider may adjust a new name, and `entry` returns the real one. `write` replaces an existing file; `export` replaces one only with `overwrite: true`.
- `import` streams a picked file into the private folder through `AppFiles.importStream` (quota-checked, published atomically); `export` streams a private file (including `photos/`) through `AppFiles.exportStream`. Both need `storage.files` (else `unavailable`). A failed export removes only a document it created itself (after Save as, only while it is still empty).
- Codes: `invalid_arguments`, `invalid_path`, `not_found`, `exists`, `not_empty`, `is_directory`, `not_directory`, `read_only`, `unavailable`, `revoked`, `quota_exceeded`, `grant_limit`, `busy`, and the broker's `gesture_required`, `blocked`, `denied` and `revoked`. Messages are at most 200 characters.
- At most 8 requests of a session wait for their replies (more answer `busy`). All work runs in order on one background thread (`pub-phone-files`) and replies are posted to the main thread. Closing, restarting or switching the app, leaving PUB, Stop access and rule changes close the broker session: its queued requests are skipped and late replies dropped. While only files are in use, the running app's bar reads "Phone file access enabled · Stop access".

### Grants and Android permissions

`filesDir/app-grants/<app id>.json`, one per saved app, written atomically (`AtomicFile`) and parsed strictly (`PhoneFileGrants.parse` rejects unknown, duplicate, missing or unnormalized fields), with at most 32 grants, unique handles and unique URIs:

```
{"version":1,"grants":[{"handle":"…","uri":"content://…","kind":"file","write":false,"name":"Report.pdf","mimeType":"application/pdf","added":1731600000000}]}
```

- **Saved apps.** On a pick PUB takes Android's persistable URI permission for read, and for write only when the app asked with `write: true` and the picker granted it; then it writes the grant file. A failure leaves neither behind. Picking the same document again keeps its handle.
- **Holder count.** One persisted permission per document serves every saved app that holds it (`PhoneFileGrants.Holds`, built from all grant files). PUB releases it (`releasePersistableUriPermission`) only when no saved app needs it any more, and releases write alone when only readers remain. All saved apps together hold at most 256 distinct documents (Android allows 512 persisted grants per app on current versions); beyond that `grant_limit` asks the user to forget an app's file access first. Per app the limit is 32.
- **New-app previews.** Grants are session-only, in memory, using the picker's temporary grant: no persistable permission is taken, so there is nothing to release. They end when the preview closes. (Android keeps the picker's temporary grant for PUB until the phone restarts and offers no way to give it up early; once the handle is gone, no app can reach it.) **Save locally does not keep them**: the saved app asks the user to pick again, so a grant never outlives what the user agreed to for a preview.
- **Edit previews.** `grants` also lists the saved app's grants (with `canWrite: false`). The preview can `stat`, `list`, `read` and `import` through them; `write`, `mkdir`, `delete`, `rename`, `export` to them and `release` answer `read_only`, and the saved grant file never changes. What the edit preview picks itself is session-only and is dropped on close or Save changes. Both kinds together stay within 32.
- **Deleting an app** releases its grants (counted) and deletes its grant file. **The user's documents are never deleted when an app is deleted.**
- **Revoking.** Rule changes and the global block in Settings → Access stop access at once (broker behaviour). Per app, **Forget file access** (shown with the number of files and folders the app holds) releases all of that app's grants; the documents stay.
- **Revoked outside PUB** (a provider revoked access, or the document was deleted elsewhere): a request that finds no permission, or a SecurityException while PUB no longer holds one, answers `revoked` and drops the grant; `grants` drops such grants too. A SecurityException while access remains is a provider refusal (`unavailable` or `read_only`) and keeps the grant. An edit preview never drops the saved app's grants.
- **Startup** (once per process, before any request): grant files of apps whose package no longer exists (a deletion cut short) are released and removed, and persisted permissions, or their write bit, that no grant needs are released, so an interrupted change cannot leave access behind. A grant file that cannot be read counts as empty (so its documents' permissions are released) and is replaced by the app's next change.
- **The picker and PUB's lifecycle.** The system picker covers PUB, so the Activity stops: other hardware sessions end as usual, but the open picker request survives (`HostModel.filePicker`). Its answer goes to a new broker session of the same app run, which keeps the asking session's consent and re-checks the rules; a different app run, a closed app or a rule change drops it. A recreated Activity does not open the picker again.

### Verification

`PhoneFilesContractTest` and `PhoneFileGrantsTest` (JVM) cover operations and arguments, document names and paths, handles, item, entry, list and read shapes and their sizes, error codes, the grant file format and the holder count. `PhoneFilesDeviceTest` (instrumentation: sandboxed grant directory and private files, an in-memory document provider and URI permissions) covers the grant store (persist, reload after a restart, holder counts, release, per-app and host limits, previews, deletion, forgetting, startup reconciliation, unreadable files), every operation on file and folder handles, import and export, edit-preview read-only rules, revocation, the broker's gesture and picker rules and that no reply contains a URI. It has not yet been run on a phone, and the real system picker and real document providers (Downloads, Drive) are untested.
