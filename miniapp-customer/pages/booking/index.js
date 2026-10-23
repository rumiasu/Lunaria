const page=require('../../utils/page');const api=require('../../utils/api');const f=require('../../utils/format')
page({data:{serviceId:'facial',day:f.today(),slots:[],selected:'',note:'',services:[],serviceIndex:0},
 onLoad(query){if(query.service)this.setData({serviceId:query.service})},
 async load(){const catalog=await api.request('/catalog');this.setData({services:catalog.services,serviceIndex:Math.max(0,catalog.services.findIndex(x=>x.id===this.data.serviceId))});await this.fetchSlots()},
 async fetchSlots(){const slots=await api.request(`/slots?serviceId=${encodeURIComponent(this.data.serviceId)}&date=${this.data.day}`);this.setData({slots:slots.map(value=>({value,label:value.slice(11,16)})),selected:''})},
 async dateChange(e){this.setData({day:e.detail.value});await this.run(()=>this.fetchSlots())},
 async serviceChange(e){const index=Number(e.detail.value);this.setData({serviceIndex:index,serviceId:this.data.services[index].id});await this.run(()=>this.fetchSlots())},
 select(e){this.setData({selected:e.currentTarget.dataset.value})},
 async submit(){await this.run(async()=>{if(!this.data.selected)throw new Error('请先选择时间');await api.mutate('/me/appointments',{serviceId:this.data.serviceId,startsAt:this.data.selected,note:this.data.note});wx.showToast({title:'已提交，待店长确认',icon:'none'});wx.switchTab({url:'/pages/appointments/index'})})}
})
