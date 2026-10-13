import { z } from "zod";

export const schema = z.object({
  id: z.string().regex(/^[a-zA-Z0-9_-]{10,}$/),
});

export type InputType = z.infer<typeof schema>;

// The response is the raw audio stream (not JSON); use this URL as an <audio> src.
export const driveStreamUrl = (id: string) =>
  `/_api/drive/stream?id=${encodeURIComponent(id)}`;
