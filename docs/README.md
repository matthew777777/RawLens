# Documentation

## Using RawLens

- [User guide](user-guide/user-guide.md): capture, development, merges and video.
- [Feature reference](FEATURES.md): supported controls and camera-dependent limits.
- [Current source state](CURRENT_STATE.md): app integration and build requirements.
- [Release history](../CHANGELOG.md) and [release notes](releases/).

## Developing RawLens

- [Development guide](DEVELOPMENT.md) and [contribution guide](../CONTRIBUTING.md).
- [F-Droid builds](fdroid.md).
- [Tools index](../tools/README.md): app build inputs, desktop launchers and manual checks.
- [Rendering](rendering/), [device profiles](device-profiles/) and
  [performance studies](performance/).

## Historical research

[Refactor studies](refactor/README.md) retain algorithm decisions, attribution,
failed experiments and measured results. They describe their original revision;
use the current source and user guide for today's behavior.

Generated build logs, recording telemetry, screenshots and one-off session
handoffs removed during repository cleanup remain available in Git history.
See the [archive guide](refactor/README.md) for the exact revision and retrieval
commands. New local reports belong under ignored `build/` or `output/` directories.
