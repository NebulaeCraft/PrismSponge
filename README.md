THIS REPO IS FOR REFERENCE ONLY! 

We hope to add sponge support to V4 currently being developed here: https://github.com/prism/PrismRefracted/tree/v4

WorldEdit / FAWE Forge112 capture, configuration, build dependencies and verification:
[WorldEdit integration](docs/worldedit.md).

Chat defaults to Simplified Chinese (`general.language="zh_CN"` in Prism's configuration).
Set it to `en_US` and restart the server to use English. Messages are maintained in
UTF-8 files under `src/main/resources/assets/prism/lang/`. Block, item and entity
names use Minecraft translation components and the client's language/resource pack;
player names, command syntax and raw record IDs remain intact.

Lookup pagination uses eight `=` characters on each side of compact clickable
`« 1/3 »` controls to fit a 237px chat box with CleanroomMC. Inspection titles and
coordinates occupy separate lines. Consoles receive all results without pagination.
