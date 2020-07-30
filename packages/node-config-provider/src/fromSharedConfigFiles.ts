import { ProviderError } from "@aws-sdk/property-provider";
import {
  loadSharedConfigFiles,
  Profile,
  SharedConfigFiles,
  SharedConfigInit as BaseSharedConfigInit,
} from "@aws-sdk/shared-ini-file-loader";
import { Provider } from "@aws-sdk/types";

const DEFAULT_PROFILE = "default";
export const ENV_PROFILE = "AWS_PROFILE";

export interface SharedConfigInit extends BaseSharedConfigInit {
  /**
   * The configuration profile to use.
   */
  profile?: string;

  /**
   * A promise that will be resolved with loaded and parsed credentials files.
   * Used to avoid loading shared config files multiple times.
   */
  loadedConfig?: Promise<SharedConfigFiles>;
}

export type GetterFromConfig<T> = (profile: Profile) => T;
// export type SharedConfigSelector<T> = T extends string ? (string | GetterFromConfig<T>) : GetterFromConfig<T>

/**
 * Get config value from the shared config files with inferred profile name.
 * Typescript generic T to specify output type. If T is string, the input param
 * can be string OR a function returns T. If T is not string, the input param
 * must be a function returns T
 */
export const fromSharedConfigFiles = <T = string>(
  //TODO: type should be SharedConfigSelector<T> but doesn't work well when T is
  //boolean, because of TS limitation: https://github.com/microsoft/TypeScript/issues/19360
  configSelector: string | GetterFromConfig<T>,
  init: SharedConfigInit = {}
): Provider<T> => async () => {
  const { loadedConfig = loadSharedConfigFiles(init), profile = process.env[ENV_PROFILE] || DEFAULT_PROFILE } = init;

  const { configFile } = await loadedConfig;

  try {
    const configValue: T | string | undefined =
      typeof configSelector === "string"
        ? configFile?.[profile]?.[configSelector]
        : configSelector(configFile[profile] || {});
    if (configValue === undefined) {
      throw new Error();
    }
    return configValue as T;
  } catch (e) {
    throw new ProviderError(
      e.message || `No ${configSelector} found for profile ${profile} in SDK configuration files`
    );
  }
};
