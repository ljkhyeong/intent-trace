use zed_extension_api::{self as zed, settings::LspSettings, LanguageServerId, Result};

/// Zed 게시 규칙에 따라 언어 서버를 포함하지 않고 사용자가 설치한 연결 도구를 실행한다.
struct IntentTraceExtension;

impl zed::Extension for IntentTraceExtension {
    fn new() -> Self {
        IntentTraceExtension
    }

    fn language_server_command(
        &mut self,
        language_server_id: &LanguageServerId,
        worktree: &zed::Worktree,
    ) -> Result<zed::Command> {
        // `intent-trace-zed configure --apply`가 저장한 실행 명령과 서버 주소를 먼저 사용한다.
        let binary = LspSettings::for_worktree(language_server_id.as_ref(), worktree)
            .ok()
            .and_then(|settings| settings.binary);
        if let Some(binary) = binary {
            if let Some(path) = binary.path {
                return Ok(zed::Command {
                    command: path,
                    args: binary.arguments.unwrap_or_default(),
                    env: binary.env.unwrap_or_default().into_iter().collect(),
                });
            }
        }
        let command = worktree.which("intent-trace-zed").ok_or_else(|| {
            "intent-trace-zed was not found. Install the IntentTrace Zed tool and run `intent-trace-zed configure --apply`.".to_string()
        })?;
        Ok(zed::Command {
            command,
            args: vec!["lsp".to_string()],
            env: Vec::new(),
        })
    }
}

zed::register_extension!(IntentTraceExtension);
