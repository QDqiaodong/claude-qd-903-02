import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 10000 })

http.interceptors.response.use(
  response => response.data,
  error => {
    const wrapped = new Error(error.response?.data?.message || error.message || '网络请求失败')
    wrapped.status = error.response?.status
    wrapped.data = error.response?.data?.data
    return Promise.reject(wrapped)
  }
)

export const yardApi = {
  zones: () => http.get('/yard/zones'),
  slots: (zone, bayStart = 1, bayEnd = 99) => http.get('/yard/slots', { params: { zone, bayStart, bayEnd } }),
  matrix: (zone, bay) => http.get(`/yard/matrix/${zone}/${bay}`),
  exact: ({ yardZone, bay, row, tier }) => http.get(`/yard/slots/${yardZone}/${bay}/${row}/${tier}`),
  occupancy: () => http.get('/yard/occupancy'),
  bayStats: (zone, bay) => http.get('/yard/bay-stats', { params: { zone, bay } }),
  tierStats: tier => http.get('/yard/tier-stats', { params: { tier } }),
  place: data => http.post('/yard/place', data),
  relocate: data => http.post('/yard/relocate', data),
  pickup: containerNo => http.post(`/yard/pickup/${containerNo}`)
}

export const berthApi = {
  shoreline: () => http.get('/berths/shoreline'),
  schedules: berthId => http.get(`/berths/${berthId}/schedules`),
  assignCranes: (scheduleId, craneCodes) => http.post(`/berths/schedules/${scheduleId}/assign-cranes`, { craneCodes }),
  setStatus: (berthId, status) => http.put(`/berths/${berthId}/status`, null, { params: { status } }),
  depart: scheduleId => http.post(`/berths/schedules/${scheduleId}/depart`)
}

export const craneApi = {
  efficiency: () => http.get('/cranes/efficiency'),
  shifts: craneId => http.get(`/cranes/${craneId}/shifts`),
  registerShift: (craneId, data) => http.post(`/cranes/${craneId}/shifts`, data),
  setStatus: (craneId, status, operator) => http.put(`/cranes/${craneId}/status`, null, { params: { status, operator } })
}

export const containerApi = {
  list: () => http.get('/containers'),
  companySummary: () => http.get('/containers/company-summary'),
  lifecycle: containerNo => http.get(`/containers/${containerNo}/lifecycle`)
}

export const operationApi = {
  gates: () => http.get('/operations/gates'),
  instructions: () => http.get('/operations/instructions'),
  createInstruction: data => http.post('/operations/instructions', data),
  start: (id, craneCode) => http.post(`/operations/instructions/${id}/start`, { craneCode }),
  advanceStage: (id, craneCode, stage) => http.post(`/operations/instructions/${id}/stage`, { craneCode, stage }),
  complete: id => http.post(`/operations/instructions/${id}/complete`)
}

export const takeoverApi = {
  overview: () => http.get('/takeover/overview'),
  plan: planId => http.get(`/takeover/plans/${planId}`),
  candidates: itemId => http.get(`/takeover/items/${itemId}/candidates`),
  decide: (planId, data) => http.post(`/takeover/plans/${planId}/decisions`, data),
  pauseAll: (planId, data) => http.post(`/takeover/plans/${planId}/pause-all`, data),
  resume: (planId, data) => http.post(`/takeover/plans/${planId}/resume`, data)
}
