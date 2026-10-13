import { z } from "zod";
import superjson from "superjson";

export const schema = z.object({
  query: z.string().trim().min(2, "Escribe al menos 2 letras").max(100, "Máximo 100 caracteres"),
});

export type InputType = z.infer<typeof schema>;

export type DriveFolderResult = {
  id: string;
  title: string;
  url: string;
};

export type OutputType = {
  results: DriveFolderResult[];
};

export type SearchError = Error & { code?: string };

export const postDriveSearch = async (
  body: InputType,
  init?: RequestInit
): Promise<OutputType> => {
  const validatedInput = schema.parse(body);
  const result = await fetch(`/_api/drive/search`, {
    method: "POST",
    body: superjson.stringify(validatedInput),
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
  });
  if (!result.ok) {
    const errorObject = superjson.parse<{ error: string; code?: string }>(await result.text());
    const err: SearchError = new Error(errorObject.error);
    err.code = errorObject.code;
    throw err;
  }
  return superjson.parse<OutputType>(await result.text());
};
