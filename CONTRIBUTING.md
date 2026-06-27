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
* **Test Your Code:** Test your changes on a real device. Make sure you don't introduce regressions.

### Creating New Visuals
If you are contributing a new visual scene:
1. Create a new `GlScene` implementation.
2. If it relies heavily on custom shaders, document the shader inputs/uniforms.
3. Keep performance in mind—we aim for low latency. Avoid heavy allocations in the `draw` loop.
4. Ensure it scales properly across different aspect ratios.

## Pull Request Process

1. Ensure your branch is up-to-date with the `main` branch.
2. Push your branch to your fork on GitHub.
3. Open a Pull Request using the provided PR template.
4. A maintainer will review your code. You might be asked to make some changes.
5. Once approved, your PR will be merged!

## Code of Conduct

By participating in this project, you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md).

Thank you for contributing! 🚀
