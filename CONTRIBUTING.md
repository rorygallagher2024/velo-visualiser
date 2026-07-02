# Contributing Guidelines

First off, thank you for considering contributing! It's people like you that make it a great tool for everyone.

## Getting Started

1. **Fork the Repository:** Start by forking the repository to your own GitHub account.
2. **Clone the Repo:** Clone your fork to your local machine.
3. **Open in Android Studio:** Open the project in Android Studio (we recommend the latest stable version).
4. **Sync Gradle:** Ensure Gradle syncs successfully.
5. **Create a Branch:** Create a new branch for your feature or bug fix (`git checkout -b feature/your-feature-name` or `bugfix/issue-description`).

## How to Contribute

### Reporting Bugs
If you find a bug, please create an issue using the Bug Report template. Include as much detail as possible:
* Android version
* Device model
* Steps to reproduce the bug
* Expected vs actual behavior
* Screenshots or videos if applicable

### Suggesting Enhancements
We welcome new ideas! If you want to propose a new feature or improvement:
* Check existing issues to see if it's already been suggested.
* Create an issue using the Feature Request template.
* Describe the feature, why it's useful, and how you envision it working.

### Contributing Code
If you want to contribute code:
* **Pick an Issue:** Find an open issue you want to work on. If it's a new feature, please open an issue to discuss it first before writing code.
* **Follow the Style:** Keep your code consistent with the existing Kotlin/C++ codebase.
* **Keep it Small:** Try to keep your pull requests small and focused on a single issue or feature.
* **Run the checks:** Before opening a PR, run **`./gradlew check`**. It runs detekt (complexity/size limits), Android lint, and the JVM unit tests — including `ShaderValidationTest`, which validates every embedded GLSL shader. Note that `assembleDebug` runs *none* of these, so a green build alone isn't enough.
* **Test Your Code:** Also test on a real device — shader rendering, audio latency, lighting and permission flows only fully surface there.

### Creating New Visuals
If you are contributing a new visual scene:
1. Create a new `GlScene` implementation.
2. If it relies heavily on custom shaders, document the shader inputs/uniforms.
3. Keep performance in mind—we aim for low latency. Avoid heavy allocations in the `draw` loop.
4. Ensure it scales properly across different aspect ratios.
5. **Shaders compile at runtime**, so a passing build doesn't prove a shader is valid. `./gradlew check` runs a headless `ShaderValidationTest` over every embedded shader; it flags the common footguns — notably GLSL ES **reserved words used as identifiers** (`sample`, `input`, `output`, `filter`, …), which compile fine on desktop tooling but crash on-device. Install the reference compiler for full syntax/type checking too: `brew install glslang` (or `apt install glslang-tools`). Always confirm the scene actually *renders* correctly on a device — a shader can compile cleanly and still look wrong.

## Pull Request Process

1. Ensure your branch is up-to-date with the `main` branch.
2. Push your branch to your fork on GitHub.
3. Open a Pull Request using the provided PR template.
4. A maintainer will review your code. You might be asked to make some changes.
5. Once approved, your PR will be merged!

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

Thank you for contributing! 🚀
