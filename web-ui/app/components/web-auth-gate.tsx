import * as React from "react";
import { useTranslation } from "react-i18next";

import { extractErrorMessage } from "~/lib/error";
import { onWebAuthRequired, requestWebAuthToken, WebAuthStorageError } from "~/services/api";
import { Button } from "~/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "~/components/ui/card";
import { Input } from "~/components/ui/input";

export function WebAuthGate() {
  const { t } = useTranslation();
  const [open, setOpen] = React.useState(false);
  const [password, setPassword] = React.useState("");
  const [submitting, setSubmitting] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);
  const inputRef = React.useRef<HTMLInputElement | null>(null);
  // 解锁框是否已因 401 打开：并发请求会各自派发一次 401，事件会重复到达。
  const openRef = React.useRef(false);

  React.useEffect(() => {
    return onWebAuthRequired(() => {
      // 401 race：解锁框已打开时，重复事件**不能**再清空用户正在输入的密码，
      // 也不能把 submitting 打回 false（否则正在提交的登录会被重新点亮，造成重复提交）。
      if (openRef.current) return;
      openRef.current = true;
      setOpen(true);
      setSubmitting(false);
      setError(null);
      setPassword("");
    });
  }, []);

  React.useEffect(() => {
    if (!open) return;
    inputRef.current?.focus();
  }, [open]);

  const onSubmit = React.useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      if (password.length === 0) {
        setError(t("web_auth_gate.password_required"));
        return;
      }

      setSubmitting(true);
      setError(null);
      try {
        await requestWebAuthToken(password);
        setOpen(false);
        openRef.current = false;
        window.location.reload();
      } catch (submitError) {
        // 存储不可用（隐私模式/站点存储被拦截）时不能假装登录成功去 reload：内存兜底会被
        // reload 丢掉，变成「登录成功→被踢回解锁页」的循环。明确提示用户放开存储后重试。
        setError(
          submitError instanceof WebAuthStorageError
            ? t("web_auth_gate.storage_unavailable")
            : extractErrorMessage(submitError, t("web_auth_gate.unlock_failed")),
        );
      } finally {
        setSubmitting(false);
      }
    },
    [password, t],
  );

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4 backdrop-blur-[2px]">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle>{t("web_auth_gate.title")}</CardTitle>
          <CardDescription>{t("web_auth_gate.description")}</CardDescription>
        </CardHeader>
        <CardContent>
          <form className="space-y-3" onSubmit={onSubmit}>
            <Input
              ref={inputRef}
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder={t("web_auth_gate.password_placeholder")}
              autoComplete="current-password"
              disabled={submitting}
            />
            {error ? <p className="text-sm text-destructive">{error}</p> : null}
            <Button className="w-full" type="submit" disabled={submitting}>
              {submitting ? t("web_auth_gate.unlocking") : t("web_auth_gate.unlock")}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}
