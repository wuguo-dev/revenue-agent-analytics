import axios from 'axios'

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
}

export const http = axios.create({ baseURL: '/api/v1', timeout: 20_000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => Promise.reject(new Error(error.response?.data?.message ?? '请求失败')),
)
