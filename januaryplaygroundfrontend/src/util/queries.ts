import {useMutation, useQuery, useQueryClient} from "@tanstack/react-query";
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

      return await extractAuth(response);
    },
    staleTime: 5 * 60 * 1000,
    refetchInterval: 60 * 1000,
  });
};


export const useLogout = () => {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: ['auth', 'logout'],
    mutationFn: async () => {
      const response = await fetch(`${getBaseUrl()}/auth/logout`, {
        method: "POST",
        credentials: "include",
      })

      if (!response.ok) {
        throw new Error(`Logout failed: ${response.status}`)
      }

      return response.json()
    },
    onSuccess: () => {
      // Clear all auth-related cache data
      queryClient.removeQueries({ queryKey: ['auth'] })

    },
    onError: () => {
      queryClient.removeQueries({ queryKey: ['auth'] })
    }
  })
}


export const useLogin = () => {
  const queryClient = useQueryClient()

  return useMutation({
    mutationKey: ['auth', 'login'],
    mutationFn: async (data: { email: string; password: string }) => {
      // Same fetch logic but cleaner
      const response = await fetch(`${getBaseUrl()}/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "include",
        body: JSON.stringify(data),
      })

      if (!response.ok) {
        const errorMessage = await response.text()
        throw new Error(errorMessage || 'Login failed')
      }

      return response.json()
    },
    onSuccess: (authData) => {
      queryClient.setQueryData(['auth'], authData)
    }
  })
}

export const useSignup = () => {
  const queryClient = useQueryClient()

  return useMutation({
    mutationKey: ['auth', 'signup'],
    mutationFn: async (credentials: { email: string; password: string }): Promise<AuthDto> => {
      const response = await fetch(`${getBaseUrl()}/auth/signup`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        credentials: "include",
        body: JSON.stringify(credentials),
      })

      if (!response.ok) {
        const errorMessage = await response.text()
        throw new Error(errorMessage || 'Signup failed')
      }

      return await extractAuth(response)
    },
    onSuccess: (authData) => {
      // Update the auth cache with new data
      queryClient.setQueryData(['auth'], authData)

      // Optionally invalidate to trigger background refetch
      queryClient.invalidateQueries({ queryKey: ['auth'] })
    }
  })
}

const extractAuth = async (response: Response) => {
  const data = await response.json()

  if (typeof data !== 'object' || data === null) {
    throw new Error('Invalid response format')
  }

  return {
    email: typeof data.email === 'string' ? data.email : null,
    loggedIn: Boolean(data.email),
    expireTime: typeof data.expireTime === 'string' || typeof data.expireTime === 'number'
      ? Number(data.expireTime)
      : -1,
    evaluated: true
  }
}
