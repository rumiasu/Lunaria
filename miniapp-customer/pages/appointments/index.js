const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{appointments:[]},async load(){const me=await api.request('/me');this.setData({appointments:me.appointments.map(f.decorate)})},
 cancel(e){wx.showModal({title:'取消这次预约',content:'取消不会扣除套餐次数，记录会保留。',success:res=>{if(res.confirm)this.run(async()=>{await api.mutate(`/me/appointments/${e.currentTarget.dataset.id}/cancel`,{reason:'顾客主动取消'});await this.load()})}})}
})
