export type ConsoleRole = 'user' | 'assistant' | 'error';

export interface ConsoleMessage {
  id: number;
  role: ConsoleRole;
  text: string;
  timestamp: number;
}

let seq = 0;

/** 单调递增 id；对话流 v-for 的 key。 */
export function nextId(): number {
  seq += 1;
  return seq;
}

/** 从持久化历史恢复后，把序号抬到已用 id 之上，避免与新建消息的 key 冲突。 */
export function ensureSeqAbove(n: number): void {
  if (n > seq) seq = n;
}
