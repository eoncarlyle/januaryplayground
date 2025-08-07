import {useQuery} from "@tanstack/react-query";
import {getBaseUrl} from "@/util/rest.ts";


import {AuthDto} from "@/util/model.ts";

export const useAuth = () => {
  return useQuery<AuthDto>({
    queryKey: ['auth'],
    queryFn: async () => {
      const response = await fetch(`${getBaseUrl()}/auth/evaluate`, {
        method: "POST",
        credentials: "include",
      });

      if (!response.ok) {
        throw new Error(`Auth check failed: ${response.status}`);
      }

      const data = await response.json();

      if (typeof data !== 'object' || data === null) {
        throw new Error('Invalid response format');
      }

      return {
        email: typeof data.email === 'string' ? data.email : null,
        loggedIn: Boolean(data.email),
        expireTime: typeof data.expireTime === 'string' || typeof data.expireTime === 'number'
          ? Number(data.expireTime)
          : -1,
        evaluated: true
      };
    },
    staleTime: 5 * 60 * 1000,
    refetchInterval: 60 * 1000,
  });
};
