import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it, vi } from "vitest";

import { activate } from "./index.js";

describe("qqdm plugin scaffold", () => {
  it("ships the plugin manifest", () => {
    const manifestPath = resolve(import.meta.dirname, "..", "openclaw.plugin.json");
    expect(existsSync(manifestPath)).toBe(true);
  });

  it("brands the package metadata as ink.easycode.qqclaw", () => {
    const packageJsonPath = resolve(import.meta.dirname, "..", "package.json");
    const packageJson = JSON.parse(readFileSync(packageJsonPath, "utf-8"));
    const manifestPath = resolve(import.meta.dirname, "..", "openclaw.plugin.json");
    const manifest = JSON.parse(readFileSync(manifestPath, "utf-8"));

    expect(packageJson.name).toBe("ink.easycode.qqclaw");
    expect(packageJson.author).toBe("Obisidian");
    expect(packageJson.organization).toBe("easycode");
    expect(manifest.name).toContain("QQClaw");
  });

  it("registers the qqdm channel", async () => {
    const api = {
      registerChannel: vi.fn(),
    };

    await activate(api as never);

    expect(api.registerChannel).toHaveBeenCalledWith(
      expect.objectContaining({
        plugin: expect.objectContaining({ id: "qqdm" }),
      }),
    );
  });
});
