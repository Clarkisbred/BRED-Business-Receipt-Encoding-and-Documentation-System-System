import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

function json(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new HttpError(500, "The shared AI service is not configured.");
  return value;
}

async function authenticate(request: Request) {
  const url = requiredEnv("SUPABASE_URL");
  const anonKey = requiredEnv("SUPABASE_ANON_KEY");
  const authorization = request.headers.get("Authorization");
  if (!authorization) throw new HttpError(401, "Authentication required.");

  const caller = createClient(url, anonKey, {
    global: { headers: { Authorization: authorization } },
  });
  const { data, error } = await caller.auth.getUser();
  if (error || !data.user) throw new HttpError(401, "Authentication required.");

  const service = createClient(url, requiredEnv("SUPABASE_SERVICE_ROLE_KEY"));
  const { data: profile, error: profileError } = await service
    .from("profiles")
    .select("role,is_active,account_status")
    .eq("id", data.user.id)
    .maybeSingle();
  if (profileError) throw new HttpError(500, "Could not verify account permissions.");
  if (!profile || !profile.is_active || profile.account_status !== "APPROVED") {
    throw new HttpError(403, "The account is not authorized to use Ducky.");
  }
  return { userId: data.user.id, role: profile.role as string, service };
}

function validModel(value: unknown): string {
  if (typeof value !== "string") throw new HttpError(400, "A valid Groq model is required.");
  const model = value.trim();
  if (!model || model.length > 120 || /[\r\n]/.test(model)) {
    throw new HttpError(400, "A valid Groq model is required.");
  }
  return model;
}

async function getModel(service: ReturnType<typeof createClient>): Promise<string> {
  const { data, error } = await service
    .from("ducky_ai_config")
    .select("model")
    .eq("id", 1)
    .maybeSingle();
  if (error) throw new HttpError(500, "Could not read the shared AI configuration.");
  return data?.model ? validModel(data.model) : "llama-3.3-70b-versatile";
}

async function groqRequest(model: string, messages: unknown[], tools: unknown[] | undefined) {
  const apiKey = Deno.env.get("GROQ_API_KEY");
  if (!apiKey) return { status: "not_configured", groqStatus: 0 };

  const payload: Record<string, unknown> = {
    model,
    temperature: 0.2,
    messages,
  };
  if (tools && tools.length > 0) {
    payload.tools = tools;
    payload.tool_choice = "auto";
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 60_000);
  try {
    const response = await fetch("https://api.groq.com/openai/v1/chat/completions", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
    const body = await response.text();
    if (!response.ok) {
      if (response.status === 401 || response.status === 403) {
        return { status: "connection_failed", groqStatus: response.status };
      }
      if (response.status === 429) {
        throw new HttpError(429, "Ducky is busy. Please wait briefly and try again.");
      }
      if (response.status >= 500) {
        throw new HttpError(503, "Ducky AI is temporarily unavailable.");
      }
      throw new HttpError(502, "Groq rejected the shared AI request.");
    }
    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(body);
    } catch {
      throw new HttpError(502, "The shared AI service returned a malformed response.");
    }
    const choices = parsed.choices as unknown;
    if (!Array.isArray(choices) || choices.length === 0) {
      throw new HttpError(502, "The shared AI service returned an empty response.");
    }
    const message = (choices[0] as Record<string, unknown>).message as Record<string, unknown> | undefined;
    if (!message) throw new HttpError(502, "The shared AI service returned a malformed response.");
    const answer = typeof message.content === "string" ? message.content : "";
    const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
    return { status: "connected", groqStatus: response.status, answer, tool_calls: toolCalls };
  } catch (error) {
    if (error instanceof HttpError) throw error;
    if (controller.signal.aborted) throw new HttpError(504, "Ducky AI timed out. Please try again.");
    throw new HttpError(503, "Could not reach Ducky AI.");
  } finally {
    clearTimeout(timer);
  }
}

function parseChatBody(body: Record<string, unknown>) {
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    throw new HttpError(400, "At least one chat message is required.");
  }
  if (body.messages.length > 30) throw new HttpError(400, "The conversation is too long.");
  for (const message of body.messages) {
    if (!message || typeof message !== "object") {
      throw new HttpError(400, "A valid chat message is required.");
    }
    const item = message as Record<string, unknown>;
    if (!["system", "user", "assistant", "tool"].includes(String(item.role))) {
      throw new HttpError(400, "Invalid chat message role.");
    }
  }
  const tools = body.tools === undefined ? undefined : body.tools;
  if (tools !== undefined && (!Array.isArray(tools) || tools.length > 20)) {
    throw new HttpError(400, "Invalid tool definitions.");
  }
  return {
    messages: body.messages,
    tools: tools as unknown[] | undefined,
  };
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);

  try {
    let body: Record<string, unknown>;
    try {
      body = await request.json();
    } catch {
      throw new HttpError(400, "A valid JSON request body is required.");
    }
    if (!body || typeof body !== "object") throw new HttpError(400, "A valid request body is required.");

    const auth = await authenticate(request);
    const action = typeof body.action === "string" ? body.action : "chat";

    if (action === "status") {
      const model = await getModel(auth.service);
      const keyConfigured = Boolean(Deno.env.get("GROQ_API_KEY"));
      return json({
        status: keyConfigured ? "connected" : "not_configured",
        model,
        credential: keyConfigured ? "configured" : "not_configured",
      });
    }

    if (action === "configure") {
      if (auth.role !== "creator") throw new HttpError(403, "Only the Creator can manage Ducky AI settings.");
      const model = validModel(body.model);
      const { error } = await auth.service
        .from("ducky_ai_config")
        .upsert({ id: 1, model, updated_by: auth.userId, updated_at: new Date().toISOString() },
          { onConflict: "id" });
      if (error) throw new HttpError(500, "Could not save the shared AI configuration.");
      const keyConfigured = Boolean(Deno.env.get("GROQ_API_KEY"));
      return json({
        status: keyConfigured ? "connected" : "not_configured",
        model,
        credential: keyConfigured ? "configured" : "not_configured",
      });
    }

    if (action === "test") {
      if (auth.role !== "creator") throw new HttpError(403, "Only the Creator can test Ducky AI settings.");
      const model = validModel(body.model);
      const result = await groqRequest(model, [
        { role: "user", content: "Reply with OK." },
      ], undefined);
      return json({
        status: result.status,
        model,
        credential: result.status === "not_configured" ? "not_configured" : "configured",
      });
    }

    if (action !== "chat") throw new HttpError(400, "Invalid Ducky AI action.");
    const chat = parseChatBody(body);
    const model = await getModel(auth.service);
    const result = await groqRequest(model, chat.messages, chat.tools);
    if (result.status === "not_configured") {
      throw new HttpError(503, "Ducky AI is currently unavailable. Please contact your system administrator.");
    }
    if (result.status === "connection_failed") {
      throw new HttpError(503, "Ducky AI is currently unavailable. Please contact your system administrator.");
    }
    return json({
      answer: result.answer,
      tool_calls: result.tool_calls,
    });
  } catch (error) {
    if (error instanceof HttpError) return json({ error: error.message }, error.status);
    console.error("Unhandled du-- ai error");
    return json({ error: "Ducky AI is temporarily unavailable." }, 500);
  }
});
