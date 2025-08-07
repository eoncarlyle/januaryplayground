export interface AuthDto {
  email: string | null;
  loggedIn: boolean;
  expireTime: number;
  evaluated: boolean;
}
