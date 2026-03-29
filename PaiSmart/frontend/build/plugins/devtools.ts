import VueDevtools from 'vite-plugin-vue-devtools';

export function setupDevtoolsPlugin(viteEnv: Env.ImportMeta) {
  const { VITE_DEVTOOLS_LAUNCH_EDITOR } = viteEnv;

  // Only enable devtools in development mode to avoid localStorage errors during config loading
  if (process.env.NODE_ENV === 'production') {
    return null;
  }

  return VueDevtools({
    launchEditor: VITE_DEVTOOLS_LAUNCH_EDITOR
  });
}
