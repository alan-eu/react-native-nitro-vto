import * as React from "react";
import {
  findNodeHandle,
  type HostComponent,
  requireNativeComponent,
  UIManager,
  type ViewProps,
} from "react-native";
import type { VtoCommonMethods, VtoCommonProps } from "./types";

/**
 * Wire-level props exposed by the native RCTViewManager. Callbacks come in as
 * event blocks wrapping a payload dict — we unwrap them before handing the
 * value to the user-supplied callback.
 */
type ModelLoadedEvent = Readonly<{ nativeEvent: { modelUrl: string } }>;
type FaceTrackedEvent = Readonly<{ nativeEvent: Record<string, never> }>;
type GlassesDisplayedEvent = Readonly<{ nativeEvent: { modelUrl: string } }>;

interface NativeVtoViewProps
  extends Omit<
      VtoCommonProps,
      "onModelLoaded" | "onFaceTracked" | "onGlassesDisplayed"
    >,
    ViewProps {
  onModelLoaded?: (event: ModelLoadedEvent) => void;
  onFaceTracked?: (event: FaceTrackedEvent) => void;
  onGlassesDisplayed?: (event: GlassesDisplayedEvent) => void;
}

const NativeVtoView =
  requireNativeComponent<NativeVtoViewProps>("VtoView") as HostComponent<NativeVtoViewProps>;

export type VtoViewProps = VtoCommonProps & ViewProps;
export type VtoRef = VtoCommonMethods;

/**
 * Old-architecture React Native view for glasses virtual try-on.
 *
 * Props, methods, and callbacks match `@alaneu/react-native-nitro-vto` — the
 * only difference is this package doesn't require `react-native-nitro-modules`
 * and works on React Native old architecture.
 */
export const VtoView = React.forwardRef<VtoRef, VtoViewProps>((props, ref) => {
  const nativeRef =
    React.useRef<React.ElementRef<typeof NativeVtoView> | null>(null);

  const {
    onModelLoaded,
    onFaceTracked,
    onGlassesDisplayed,
    ...nativeProps
  } = props;

  React.useImperativeHandle(
    ref,
    () => ({
      switchModel(modelUrl: string) {
        dispatchCommand(nativeRef.current, "switchModel", [modelUrl]);
      },
      resetSession() {
        dispatchCommand(nativeRef.current, "resetSession", []);
      },
    }),
    [],
  );

  return (
    <NativeVtoView
      {...nativeProps}
      ref={nativeRef}
      onModelLoaded={
        onModelLoaded
          ? (e) => onModelLoaded(e.nativeEvent.modelUrl)
          : undefined
      }
      onFaceTracked={onFaceTracked ? () => onFaceTracked() : undefined}
      onGlassesDisplayed={
        onGlassesDisplayed
          ? (e) => onGlassesDisplayed(e.nativeEvent.modelUrl)
          : undefined
      }
    />
  );
});

VtoView.displayName = "VtoView";

const dispatchCommand = (
  view: React.ElementRef<typeof NativeVtoView> | null,
  command: string,
  args: unknown[],
) => {
  const tag = findNodeHandle(view);
  if (tag == null) return;
  // String-form dispatchViewManagerCommand works on all modern RN old-arch
  // versions; the numeric form is codegen-specific and we don't need it here.
  UIManager.dispatchViewManagerCommand(tag, command, args);
};
