# AGENTS.md

This file provides guidance to person when working with code in this repository.
---
#### Mandatory Constraint：Document Submission/Push Authorization Rule
**Priority**: Highest level, overrides all document operation logic and cannot be bypassed
**Applicable Scenarios**: All operations involving external submission, push, or release of documents

   ##### Core Execution Rules
1.  **Pre-authorization Requirement**: An authorization request must be proactively submitted to the user before executing any submission or push action. No submission/push operation shall be triggered before valid authorization is obtained.
2.  **Unauthorized Fallback Mechanism**: If valid user authorization is not obtained, the current document content must be saved as a draft. No external submission or push action shall be performed.
3.  **Secondary Confirmation for Partial Authorization**: If only partial content of the document is authorized by the user, the full document shall not be submitted directly. The final complete version with all changes integrated must be sent to the user for confirmation prior to submission. The submission operation may only be executed after the user explicitly approves the final version.

   #####Boundary Definitions
- Valid Authorization: A clear consent statement from the user (e.g. "agree", "confirm submission", "okay to push") shall be deemed as valid authorization obtained.
- Unauthorized Determination: No reply from the user, ambiguous expression, or only approval of partial content shall all be judged as failure to obtain full submission authorization.
---

## Project Overview

RikkaHub is a native Android LLM chat client that supports switching between different AI providers for conversations.
Built with Jetpack Compose, Kotlin, and follows Material Design 3 principles.

## Architecture Overview

### Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.
- **terminal**: Terminal emulator core taken from termux-app's `terminal-emulator` (package `com.termux.terminal`, kept
  binary compatible with the `terminal-view` aar). `TerminalSession` runs over a `TerminalTransport`: local PRoot
  terminals use `LocalPtyTransport`, SSH workspaces use the app's `SshTerminalTransport`. Origin and local changes are
  listed in terminal/README.md.

### Key Technologies

- **Jetpack Compose**: Modern UI toolkit
- **Koin**: Dependency injection
- **Room**: Database ORM
- **DataStore**: Preferences storage
- **OkHttp**: HTTP client with SSE support
- **Navigation 3**: App navigation
- **Kotlinx Serialization**: JSON handling

### Core Packages (app module)

- `data/`: Data layer with repositories, database entities, and API clients
- `ui/pages/`: Screen implementations and ViewModels
- `ui/components/`: Reusable UI components
- `di/`: Dependency injection modules
- `utils/`: Utility functions and extensions

### Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT, SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables users to regenerate responses and switch between different conversation branches, creating a tree-like conversation structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

## Development Guidelines

### UI Development

- Follow Material Design 3 principles
- Use existing UI components from `ui/components/`
- Reference `SettingProviderPage.kt` for page layout patterns
- Use `FormItem` for consistent form layouts
- Implement proper state management with ViewModels
- Use `Lucide.XXX` for icons, and import `import com.composables.icons.lucide.XXX` for each icon
- Use `LocalToaster.current` for toast messages

### Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- If the user explicitly requests localization, all languages should be supported.
- English(en) is the default language. Chinese(zh) and Traditional Chinese(zh-rTW) are supported.
- When localization is needed, use the `locale-tui-localization` skill for managing string resources.
