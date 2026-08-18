import { version } from "../package.json";

export { VtoView, type VtoViewProps, type VtoRef } from "./VtoView";
export type {
  VtoCommonProps,
  VtoCommonMethods,
  ArUnavailableReason,
} from "./types";

export const vtoVersion = version;
